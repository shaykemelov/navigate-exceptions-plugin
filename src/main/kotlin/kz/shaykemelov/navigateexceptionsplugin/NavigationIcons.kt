package kz.shaykemelov.navigateexceptionsplugin

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object NavigationIcons {

    val THROW_ICON: Icon = IconLoader.getIcon("/throw.svg", NavigationIcons::class.java)
    val CATCH_ICON: Icon = IconLoader.getIcon("/catch.svg", NavigationIcons::class.java)
}
