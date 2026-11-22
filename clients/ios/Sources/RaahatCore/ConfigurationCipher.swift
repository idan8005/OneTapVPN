import CryptoKit
import Foundation

public enum ConfigurationCipherError: Error {
    case invalidKeyLength
    case missingCombinedRepresentation
}

/// Authenticated encryption for sensitive client configuration persisted at rest.
public enum ConfigurationCipher {
    /// Encrypts data with AES-256-GCM. The returned combined representation includes nonce and tag.
    public static func seal(_ plaintext: Data, keyData: Data) throws -> Data {
        guard keyData.count == 32 else {
            throw ConfigurationCipherError.invalidKeyLength
        }
        let key = SymmetricKey(data: keyData)
        let sealedBox = try AES.GCM.seal(plaintext, using: key)
        guard let combined = sealedBox.combined else {
            throw ConfigurationCipherError.missingCombinedRepresentation
        }
        return combined
    }

    /// Authenticates and decrypts an AES-256-GCM combined representation.
    public static func open(_ combined: Data, keyData: Data) throws -> Data {
        guard keyData.count == 32 else {
            throw ConfigurationCipherError.invalidKeyLength
        }
        let key = SymmetricKey(data: keyData)
        let sealedBox = try AES.GCM.SealedBox(combined: combined)
        return try AES.GCM.open(sealedBox, using: key)
    }
}
