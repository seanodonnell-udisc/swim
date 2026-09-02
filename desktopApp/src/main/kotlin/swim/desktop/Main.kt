package swim.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import swim.ui.App

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Swim") {
        App()
    }
}
