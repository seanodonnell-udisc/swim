import UIKit
import SwiftUI
import ComposeApp

struct ComposeLeaf: UIViewControllerRepresentable {
    let make: () -> UIViewController
    func makeUIViewController(context: Context) -> UIViewController { make() }
    func updateUIViewController(_ vc: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeLeaf(make: { MainViewControllerKt.MainViewController() })
            .ignoresSafeArea(.all)
    }
}
