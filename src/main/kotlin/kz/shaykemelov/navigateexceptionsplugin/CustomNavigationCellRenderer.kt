package kz.shaykemelov.navigateexceptionsplugin

import com.intellij.ide.util.PsiElementListCellRenderer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement

class CustomNavigationCellRenderer : PsiElementListCellRenderer<PsiElement>() {
    override fun getElementText(element: PsiElement?): String {
        if (element is PsiNamedElement) {
            return element.name!!
        }
        return element!!.text
    }

    override fun getContainerText(element: PsiElement?, name: String?): String? {
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
