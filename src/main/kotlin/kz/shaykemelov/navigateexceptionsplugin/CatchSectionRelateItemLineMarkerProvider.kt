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

        val dataContext = DataManager.getInstance().dataContextFromFocusAsync.blockingGet(1, TimeUnit.MINUTES)
        val project = dataContext!!.getData(PlatformDataKeys.PROJECT)!! // FIXME LATER !!

        val throwStatements = findAllThrowStatements(project, element)

        val builder = NavigationGutterIconBuilder.create(NavigationIcons.CATCH_ICON)
            .setTargets(throwStatements)
            .setTooltipText("Navigate to related throw statements")

        val lineMarkerInfo = builder.createLineMarkerInfo(element)

        result.add(lineMarkerInfo)
    }

    private fun findAllThrowStatements(
        project: Project,
        catchSection: PsiCatchSection
    ): List<PsiThrowStatement> {

        val methodCallExpressions = mutableListOf<PsiMethodCallExpression>()
        recursivelyFindAllMethodCallExpressions(project, catchSection.tryStatement.tryBlock!!, methodCallExpressions)

        val throwStatements = mutableListOf<PsiThrowStatement>()
        methodCallExpressions
            .mapNotNull { it.resolveMethod() }
            .forEach { resolvedMethod ->
                if (resolvedMethod.containingClass?.isInterface == true) {
                    ClassInheritorsSearch.search(resolvedMethod.containingClass!!)
                        .forEach { findAllThrowStatements(project, it.findMethodBySignature(resolvedMethod, true), catchSection.catchType!!, throwStatements) }
                } else {
                    findAllThrowStatements(project, resolvedMethod, catchSection.catchType!!, throwStatements)
                }

            }

        return throwStatements
    }

    private fun findAllThrowStatements(
        project: Project,
        method: PsiMethod?,
        catchingException: PsiType,
        resultCollector: MutableList<PsiThrowStatement>
    ) {

        method?.children?.forEach { recursivelyFindAllThrowStatements(project, it, catchingException, resultCollector) }
    }

    private fun recursivelyFindAllThrowStatements(
        project: Project,
        element: PsiElement,
        catchingException: PsiType,
        resultCollector: MutableList<PsiThrowStatement>
    ) {

        if (element.containingFile.project != project) {
            return
        }

        if (element is PsiThrowStatement) {
            if (catchingException.isAssignableFrom(element.exception?.type!!)) {
                resultCollector.add(element)
            }
        }

        element.children.forEach { recursivelyFindAllThrowStatements(project, it, catchingException, resultCollector) }
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
