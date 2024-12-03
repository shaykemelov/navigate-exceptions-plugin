package kz.shaykemelov.navigateexceptionsplugin

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.JavaElementType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.MethodSignature
import com.intellij.psi.util.elementType
import com.jetbrains.rd.util.first
import java.util.concurrent.TimeUnit

class ThrowStatementRelatedItemLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {

        if (element !is PsiThrowStatement) {
            return
        }

        val catchSectionsV2 = findCatchSectionsHandlingThrowStatement(element)

        if (catchSectionsV2.isNotEmpty()) {

            val builder = NavigationGutterIconBuilder.create(NavigationIcons.THROW_ICON)
                .setTargets(catchSectionsV2)
                .setTooltipText("Navigate to related catch statement")

            val lineMarkerInfo = builder.createLineMarkerInfo(element)

            result.add(lineMarkerInfo)
            return
        }

        val catchSections = findCatchSectionsHandlingException(element)

        val builder = NavigationGutterIconBuilder.create(NavigationIcons.THROW_ICON)
            .setTargets(catchSections)
            .setTooltipText("Navigate to related catch statement")

        val lineMarkerInfo = builder.createLineMarkerInfo(element)

        result.add(lineMarkerInfo)
    }

    // new logic
    private fun findCatchSectionsHandlingThrowStatement(throwStatement: PsiThrowStatement): List<PsiCatchSection> {

        val originCatchSection = findCatchSectionHandlingThrowStatement(throwStatement, throwStatement.exception)

        if (originCatchSection != null) {
            return listOf(originCatchSection)
        }

        val catchSectionsCollector = mutableListOf<PsiCatchSection>()

        val startMethod = getMethod(throwStatement) ?: return emptyList()

        val dataContext = DataManager.getInstance().dataContextFromFocusAsync.blockingGet(1, TimeUnit.MINUTES)
        val project = dataContext!!.getData(PlatformDataKeys.PROJECT)!! // FIXME LATER !!
        val scope = GlobalSearchScope.projectScope(project)

        val methodsVisitingQueue = linkedMapOf(Pair(startMethod.getSignature(PsiSubstitutor.EMPTY), startMethod))
        startMethod.findSuperMethods().forEach { superMethod -> methodsVisitingQueue[superMethod.getSignature(PsiSubstitutor.EMPTY)] = superMethod }

        while (methodsVisitingQueue.isNotEmpty()) {

            val visitingMethod = methodsVisitingQueue.first()
            methodsVisitingQueue.remove(visitingMethod.key)

            MethodReferencesSearch.search(visitingMethod.value, scope, true).forEach { reference ->
                val resolvedVisitingMethodElement = reference.element
                val catchSection =
                    findCatchSectionHandlingThrowStatement(resolvedVisitingMethodElement, throwStatement.exception)
                if (catchSection != null) {
                    catchSectionsCollector.add(catchSection)
                } else {
                    val method = getMethod(resolvedVisitingMethodElement)

                    if (method != null) {
                        methodsVisitingQueue[method.getSignature(PsiSubstitutor.EMPTY)] = method
                        method.findSuperMethods().forEach { superMethod -> methodsVisitingQueue[superMethod.getSignature(PsiSubstitutor.EMPTY)] = superMethod }
                    }
                }
            }
        }

        return catchSectionsCollector
    }

    private fun findCatchSectionHandlingThrowStatement(
        element: PsiElement?,
        throwingExpression: PsiExpression?
    ): PsiCatchSection? {

        if (element == null) {
            return null
        }

        var parent = element.parent

        do {

            if (parent is PsiTryStatement) {
                val catchSection = parent.catchSections.firstOrNull { catchSection ->
                    isExceptionHandled(catchSection.catchType!!, throwingExpression!!)
                }

                if (catchSection != null) {
                    return catchSection
                }
            }

            parent = parent.parent
        } while (parent != null)

        return null
    }
    //

    private fun findCatchSectionsHandlingException(
        throwStatement: PsiThrowStatement
    ): List<PsiCatchSection> {

        val startMethod = getMethod(throwStatement) ?: return emptyList()

        val dataContext = DataManager.getInstance().dataContextFromFocusAsync.blockingGet(1, TimeUnit.MINUTES)
        val project = dataContext!!.getData(PlatformDataKeys.PROJECT)!! // FIXME LATER !!
        val scope = GlobalSearchScope.projectScope(project)

        val methods = mutableMapOf(startMethod.getSignature(PsiSubstitutor.EMPTY) to startMethod)
        startMethod.findSuperMethods().forEach { methods[it.getSignature(PsiSubstitutor.EMPTY)] = it }

        val globalVisitedMethods = mutableSetOf(startMethod.getSignature(PsiSubstitutor.EMPTY))
        val catchSections = mutableListOf<PsiCatchSection>()

        while (methods.isNotEmpty()) {
            val visitingMethod = methods.first()
            methods.remove(visitingMethod.key)

            val localVisitedMethods = mutableSetOf(visitingMethod.key)
            val localMethodsToVisit = mutableMapOf<MethodSignature, PsiMethod>()
            MethodReferencesSearch.search(visitingMethod.value, scope, true).forEach { reference ->

                if (!isReferenceIsInRealJavaCode(reference)) {
                    return@forEach
                }

                if (reference.element.elementType !is JavaElementType.JavaCompositeElementType) {
                    return@forEach
                }

                val methodToVisit = getMethod(reference.element)
                if (methodToVisit == null
                    || globalVisitedMethods.contains(methodToVisit.getSignature(PsiSubstitutor.EMPTY))
                ) {
                    return@forEach
                }

                localVisitedMethods.add(methodToVisit.getSignature(PsiSubstitutor.EMPTY))

                val tryStatement = getTryStatement(reference)
                val catches = findAllCatchesHandlingException(tryStatement, throwStatement.exception)
                if (catches.isEmpty()) {
                    localMethodsToVisit[methodToVisit.getSignature(PsiSubstitutor.EMPTY)] = methodToVisit
                } else {
                    catchSections.addAll(catches)
                }
            }
            methods.putAll(localMethodsToVisit)
            globalVisitedMethods.addAll(localVisitedMethods)
        }

        return catchSections
    }

    private fun getMethod(element: PsiElement?): PsiMethod? {

        if (element == null) {
            return null
        }

        // Get the parent of the throw statement, which should be the method containing it
        var parent: PsiElement? = element.parent

        // Traverse upwards in the PSI tree to find the containing method
        while (parent != null && parent !is PsiMethod) {
            parent = parent.parent
        }

        return parent as? PsiMethod
    }

    private fun isReferenceIsInRealJavaCode(reference: PsiReference): Boolean {

        val containingFile = reference.element.containingFile

        if (containingFile !is PsiJavaFile) {
            return false
        }

        var parent = reference.element.parent
        while (parent != null) {
            if (parent is PsiComment) {
                return false
            }
            parent = parent.parent
        }

        return true
    }

    private fun findAllCatchesHandlingException(
        tryStatement: PsiTryStatement?,
        thrownException: PsiExpression?
    ): List<PsiCatchSection> {

        if (tryStatement == null || thrownException == null) {
            return listOf()
        }

        return tryStatement.catchSections.filter { catchSection ->
            val catchParameter = catchSection.parameter
            val catchType = catchParameter?.type

            isExceptionHandled(catchType, thrownException)
        }
    }

    private fun getTryStatement(reference: PsiReference): PsiTryStatement? {
        var parent: PsiElement? = reference.element.parent

        while (parent != null) {
            if (parent is PsiTryStatement) {
                return parent
            }
            parent = parent.parent
        }

        return null
    }

    private fun isExceptionHandled(catchType: PsiType?, thrownException: PsiExpression): Boolean {
        val thrownType = thrownException.type
        return thrownType != null && catchType?.isAssignableFrom(thrownType) ?: false
    }
}
