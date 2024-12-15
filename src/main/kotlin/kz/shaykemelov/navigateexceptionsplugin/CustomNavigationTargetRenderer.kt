package kz.shaykemelov.navigateexceptionsplugin

import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.presentation.java.SymbolPresentationUtil
import org.jetbrains.annotations.Nls

class CustomNavigationTargetRenderer : PsiTargetPresentationRenderer<PsiElement>() {
    @Nls
    override fun getElementText(element: PsiElement): String = SymbolPresentationUtil.getSymbolPresentableText(element)

    @Nls
    override fun getContainerText(element: PsiElement): String? {
        val containingFile = element!!.containingFile
        if (containingFile != null) {
            val fileName = containingFile.name
            val lineNumber = getLineNumber(element)
            return "$fileName:$lineNumber"
        }
        return null
    }

    private fun getLineNumber(element: PsiElement): Int {
        val containingFile = element.containingFile
        if (containingFile != null) {
            val document = PsiDocumentManager.getInstance(element.project).getDocument(containingFile)
            if (document != null) {
                val offset = element.textOffset
                return document.getLineNumber(offset) + 1
            }
        }
        return -1
    }
}