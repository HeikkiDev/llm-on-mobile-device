import LiteRTLM
import Observation

/// The only state holder of the app. Engine, download and translation state land here in I2.
@Observable @MainActor
final class TranslatorModel {
    static let backend: Backend = .cpu()
}
