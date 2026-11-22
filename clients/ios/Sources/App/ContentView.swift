import SwiftUI

struct ContentView: View {
    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "shield.lefthalf.filled")
                .font(.system(size: 64))
                .foregroundStyle(.blue)
            Text("راحت وی‌پی‌ان")
                .font(.largeTitle.bold())
            Text("وضعیت اتصال: قطع است")
                .font(.title3)
            Text("موتور VPN هنوز به این نسخه متصل نشده است.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button("اتصال") {}
                .buttonStyle(.borderedProminent)
                .disabled(true)
            Text("نسخه 0.1.0-dev")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(28)
    }
}
