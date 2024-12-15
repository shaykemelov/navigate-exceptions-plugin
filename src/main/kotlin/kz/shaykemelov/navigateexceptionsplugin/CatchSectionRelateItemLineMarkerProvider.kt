package kz.shaykemelov.navigateexceptionsplugin

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.searches.ClassInheritorsSearch
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

        val throwStatementsCollector = mutableListOf<PsiThrowStatement>()

        val visitingElementsQueue = mutableListOf<PsiElement>()
        visitingElementsQueue.add(catchSection.tryStatement.tryBlock!!)

        while (visitingElementsQueue.isNotEmpty()) {

            val visitingElement = visitingElementsQueue.removeFirst()
            goDeepAndCollectThrowsWithMatchingException(
                visitingElement,
                catchSection.catchType!!,
                throwStatementsCollector,
                null
            )

            val methodCallExpressions = mutableListOf<PsiMethodCallExpression>()
            recursivelyFindAllMethodCallExpressions(project, visitingElement, methodCallExpressions)

            methodCallExpressions
                .mapNotNull(PsiMethodCallExpression::resolveMethod)
                .forEach { method ->
                    if (method.containingClass?.isInterface == true) {
                        ClassInheritorsSearch.search(method.containingClass!!).forEach { clazz ->
                            val methodInClazz = clazz.findMethodBySignature(method, true)
                            methodInClazz?.children?.forEach { child -> visitingElementsQueue.add(child) }
                        }
                    } else {
                        method.children.forEach { child -> visitingElementsQueue.add(child) }
                    }
                }
        }

        return throwStatementsCollector
    }

    private fun goDeepAndCollectThrowsWithMatchingException(
        element: PsiElement,
        catchingException: PsiType,
        throwStatementsCollector: MutableList<PsiThrowStatement>,
        tryStatement: PsiTryStatement?
    ) {
        if (element is PsiTryStatement) {
            element.tryBlock?.children?.forEach { child ->
                goDeepAndCollectThrowsWithMatchingException(child, catchingException, throwStatementsCollector, element)
            }
            element.catchSections.forEach { child ->
                goDeepAndCollectThrowsWithMatchingException(child, catchingException, throwStatementsCollector, tryStatement)
            }
            element.finallyBlock?.children?.forEach { child ->
                goDeepAndCollectThrowsWithMatchingException(child, catchingException, throwStatementsCollector, tryStatement)
            }
        } else if (element is PsiThrowStatement) {
            if (tryStatement == null) {
                throwStatementsCollector.add(element)
                return
            }

            tryStatement.catchSections.any { catchSection ->
                catchSection.catchType?.isAssignableFrom(catchingException) == true
            }
        } else {
            element.children.forEach { child ->
                goDeepAndCollectThrowsWithMatchingException(child, catchingException, throwStatementsCollector, tryStatement)
            }
        }
    }

    private fun recursivelyFindAllMethodCallExpressions(
        project: Project,
        element: PsiElement,
        resultCollector: MutableList<PsiMethodCallExpression>
    ) {

        if (element.containingFile.project != project) {
            return
        }

        if (element is PsiMethodCallExpression) {
            resultCollector.add(element)
            return
        }

        element.children.forEach { recursivelyFindAllMethodCallExpressions(project, it, resultCollector) }
    }
}
