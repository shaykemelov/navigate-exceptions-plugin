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

        val method = getMethod(element)
        val throwStatements = findCatchSectionsHandlingException(method!!, element.containingFile!!, element.exception!!)

        val builder = NavigationGutterIconBuilder.create(NavigationIcons.THROW_ICON)
            .setTargets(throwStatements)
            .setTooltipText("Navigate to related catch statement")

        val lineMarkerInfo = builder.createLineMarkerInfo(element)

        result.add(lineMarkerInfo)
    }

    private fun getMethod(throwStatement: PsiElement): PsiMethod? {
        // Get the parent of the throw statement, which should be the method containing it
        var parent: PsiElement? = throwStatement.parent

        // Traverse upwards in the PSI tree to find the containing method
        while (parent != null && parent !is PsiMethod) {
            parent = parent.parent
        }

        return parent as? PsiMethod
    }

    private fun findCatchSectionsHandlingException(
        startMethod: PsiMethod,
        file: PsiFile,
        thrownException: PsiExpression
    ): List<PsiCatchSection> {

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
                    || globalVisitedMethods.contains(methodToVisit.getSignature(PsiSubstitutor.EMPTY))) {
                    return@forEach
                }

                localVisitedMethods.add(methodToVisit.getSignature(PsiSubstitutor.EMPTY))

                val tryStatement = getTryStatement(reference)
                val catches = findAllCatchesHandlingException(tryStatement, thrownException)
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
        thrownException: PsiExpression
    ): List<PsiCatchSection> {

        if (tryStatement == null) {
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
