package kz.shaykemelov.navigateexceptionsplugin

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.MethodSignature

data class VisitingMethod(val file: PsiClass, val methodSignature: MethodSignature, val method: PsiMethod) {

    override fun equals(other: Any?): Boolean {

        if (this === other) {

            return true
        }

        if (javaClass != other?.javaClass) {

            return false
        }

        other as VisitingMethod

        if (file != other.file) {

            return false
        }

        if (methodSignature != other.methodSignature) {

            return false
        }

        return true
    }

    override fun hashCode(): Int {

        var result = file.hashCode()
        result = 31 * result + methodSignature.hashCode()

        return result
    }
}
