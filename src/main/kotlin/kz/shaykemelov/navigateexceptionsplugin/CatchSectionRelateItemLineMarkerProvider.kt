package kz.shaykemelov.navigateexceptionsplugin

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiUtil
import java.util.concurrent.TimeUnit


class CatchSectionRelateItemLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>
    ) {

        if (element !is PsiCatchSection) {
            return
        }

        if (element.catchType == null) {
            return
        }

        val dataContext = DataManager.getInstance().dataContextFromFocusAsync.blockingGet(1, TimeUnit.MINUTES)
        val project = dataContext!!.getData(PlatformDataKeys.PROJECT)!! // FIXME LATER !!

        val throwStatements = findAllThrowStatements(project, element)

        if (throwStatements.isEmpty()) {

            val builder = NavigationGutterIconBuilder.create(NavigationIcons.CATCH_ICON)
                .setTargets(emptyList())
                .setTooltipText("No related throw statement(s)")

            val lineMarkerInfo = builder.createLineMarkerInfo(element)

            result.add(lineMarkerInfo)
            return
        }

        val builder = NavigationGutterIconBuilder.create(NavigationIcons.CATCH_ICON)
            .setTargets(throwStatements)
            .setTargetRenderer { CustomNavigationTargetRenderer() }
            .setTooltipText("Navigate to related throw statement(s)")

        val lineMarkerInfo = builder.createLineMarkerInfo(element)

        result.add(lineMarkerInfo)
    }

    private fun findAllThrowStatements(
        project: Project,
        catchSection: PsiCatchSection
    ): List<PsiThrowStatement> {

        val catchingException = catchSection.catchType ?: return emptyList()
        val isCheckedException = isCheckedException(catchingException, project)

        val throwStatementsCollector = mutableListOf<PsiThrowStatement>()

        val startMethod = getMethod(catchSection) ?: return emptyList()
        val signature = startMethod.getSignature(PsiSubstitutor.EMPTY)
        val startVisitingMethod = VisitingMethod(startMethod.containingClass!!, signature, startMethod)
        val globalVisitedMethods = mutableSetOf(startVisitingMethod)

        val visitingElementsQueue = mutableListOf<PsiElement>()
        visitingElementsQueue.add(catchSection.tryStatement.tryBlock!!)

        while (visitingElementsQueue.isNotEmpty()) {

            val visitingElement = visitingElementsQueue.removeAt(0)

            visitElement(
                visitingElement,
                catchingException,
                isCheckedException,
                globalVisitedMethods,
                visitingElementsQueue,
                throwStatementsCollector
            )
        }

        return throwStatementsCollector
    }

    private fun visitElement(
        element: PsiElement,
        catchingException: PsiType,
        isCheckedException: Boolean,
        visitedMethods: MutableSet<VisitingMethod>,
        globalElementsQueue: MutableList<PsiElement>,
        throwStatementsCollector: MutableList<PsiThrowStatement>
    ) {

        val elementsQueue = mutableListOf(element)

        while (elementsQueue.isNotEmpty()) {
            when (val currentElement = elementsQueue.removeAt(0)) {
                is PsiTryStatement -> {
                    if (currentElement.catchSections
                            .any { catchSection -> catchSection.catchType?.isAssignableFrom(catchingException) == true }
                    ) {
                        currentElement.catchSections.forEach { catchSection -> elementsQueue.addAll(catchSection.children) }
                        val finally = currentElement.finallyBlock
                        if (finally != null) {

                            elementsQueue.addAll(finally.children)
                        }
                    } else {

                        elementsQueue.addAll(currentElement.children)
                    }
                }

                is PsiThrowStatement -> {

                    val currentExceptionType = currentElement.exception?.type
                    if (currentExceptionType != null && catchingException.isAssignableFrom(currentExceptionType)) {

                        throwStatementsCollector.add(currentElement)
                    }
                }

                is PsiMethodCallExpression -> {
                    elementsQueue.addAll(currentElement.children)
                    val resolvedMethod = currentElement.resolveMethod() ?: continue
                    val signature = resolvedMethod.getSignature(PsiSubstitutor.EMPTY)
                    val visitingMethod = VisitingMethod(resolvedMethod.containingClass!!, signature, resolvedMethod)

                    val resolvedMethodIsInInterface = resolvedMethod.containingClass?.isInterface == true

                    if (isCheckedException) {

                        val methodThrowsCatchingCheckedException =
                            methodThrowsCheckedException(resolvedMethod, catchingException)

                        if (!methodThrowsCatchingCheckedException) {
                            continue
                        }
                    }

                    if (!visitedMethods.contains(visitingMethod)) {
                        globalElementsQueue.addAll(resolvedMethod.children)
                        visitedMethods.add(visitingMethod)
                    }

                    if (resolvedMethodIsInInterface) {

                        ClassInheritorsSearch.search(resolvedMethod.containingClass!!).forEach { clazz ->
                            val methodInClazz = clazz.findMethodBySignature(resolvedMethod, true) ?: return@forEach
                            val inheritedMethodSignature = methodInClazz.getSignature(PsiSubstitutor.EMPTY)
                            val inheritedVisitingMethod = VisitingMethod(
                                methodInClazz.containingClass!!,
                                inheritedMethodSignature,
                                methodInClazz
                            )

                            if (!visitedMethods.contains(inheritedVisitingMethod)) {

                                globalElementsQueue.addAll(methodInClazz.children)
                                visitedMethods.add(inheritedVisitingMethod)
                            }
                        }
                    }
                }

                else -> {
                    elementsQueue.addAll(currentElement.children)
                }
            }
        }
    }

    private fun isCheckedException(
        catchingException: PsiType,
        project: Project
    ): Boolean {

        val exceptionClass = PsiUtil.resolveClassInType(catchingException) ?: return false

        val runtimeExceptionClass = JavaPsiFacade.getInstance(project)
            .findClass("java.lang.RuntimeException", catchingException.resolveScope!!)!!

        return exceptionClass != runtimeExceptionClass
                && !exceptionClass.isInheritor(runtimeExceptionClass, true)
    }

    private fun methodThrowsCheckedException(method: PsiMethod, checkedException: PsiType): Boolean {

        return method.throwsList.referencedTypes.any { referenceType ->

            val exceptionClass = PsiUtil.resolveClassInType(referenceType)
            val exceptionBaseClass = PsiUtil.resolveClassInType(checkedException) ?: return false
            exceptionClass?.isInheritor(exceptionBaseClass, true) == true
        }
    }

    private fun getMethod(element: PsiElement?): PsiMethod? {

        if (element == null) {

            return null
        }

        var parent: PsiElement? = element.parent

        while (parent != null && parent !is PsiMethod) {

            parent = parent.parent
        }

        return parent as? PsiMethod
    }
}
