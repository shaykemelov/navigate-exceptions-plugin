package kz.shaykemelov.navigateexceptionsplugin

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
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

        if (element.exception == null) {

            return
        }

        val catchSectionsV2 = findCatchSectionsHandlingThrowStatement(element)

        if (catchSectionsV2.isEmpty()) {

            val builder = NavigationGutterIconBuilder.create(NavigationIcons.THROW_ICON)
                .setTargets(emptyList())
                .setTooltipText("No related catch block(s)")

            val lineMarkerInfo = builder.createLineMarkerInfo(element)

            result.add(lineMarkerInfo)
            return
        }

        val builder = NavigationGutterIconBuilder.create(NavigationIcons.THROW_ICON)
            .setTargets(catchSectionsV2)
            .setTooltipText("Navigate to related catch block(s)")

        val lineMarkerInfo = builder.createLineMarkerInfo(element)

        result.add(lineMarkerInfo)
        return
    }

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
        var hasOuterCatchSection = false

        do {

            if (parent is PsiCatchSection) {

                hasOuterCatchSection = true
            }

            if (parent is PsiTryStatement) {

                if (hasOuterCatchSection) {

                    hasOuterCatchSection = false
                } else {

                    val catchSection = parent.catchSections.firstOrNull { catchSection ->
                        isExceptionHandled(catchSection.catchType!!, throwingExpression!!)
                    }

                    if (catchSection != null) {

                        return catchSection
                    }
                }
            }

            parent = parent.parent
        } while (parent != null)

        return null
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

    private fun isExceptionHandled(catchType: PsiType?, thrownException: PsiExpression): Boolean {

        val thrownType = thrownException.type

        return thrownType != null && catchType?.isAssignableFrom(thrownType) ?: false
    }
}
