import SwiftUI

@main
struct TranslatorApp: App {
    @State private var model = TranslatorModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(model)
        }
    }
}
