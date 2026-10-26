import SwiftUI

@main
struct NaatStudioPROApp: App {
    var body: some Scene {
        WindowGroup {
            StudioScreen()
                .background(Color(red: 0.025, green: 0.08, blue: 0.16))
                .ignoresSafeArea(edges: .bottom)
        }
    }
}

private struct StudioScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> StudioViewController {
        StudioViewController()
    }

    func updateUIViewController(_ controller: StudioViewController, context: Context) {
        // The shared HTML UI owns its on-screen state.
    }
}
