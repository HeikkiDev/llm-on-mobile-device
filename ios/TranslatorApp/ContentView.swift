import SwiftUI

struct ContentView: View {
    var body: some View {
        NavigationStack {
            ContentUnavailableView(
                "Translator",
                systemImage: "character.bubble",
                description: Text("On-device translation is coming soon.")
            )
            .navigationTitle("Translate")
        }
    }
}

#Preview {
    ContentView()
        .environment(TranslatorModel())
}
