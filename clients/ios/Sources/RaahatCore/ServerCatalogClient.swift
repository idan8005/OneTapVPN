import Foundation

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum CatalogClientError: Error, Equatable {
    case httpsRequired
    case invalidResponse
    case httpStatus(Int)
}

public struct ServerCatalogClient: Sendable {
    private let baseURL: URL

    public init(baseURL: URL) throws {
        guard baseURL.scheme?.lowercased() == "https" else {
            throw CatalogClientError.httpsRequired
        }
        self.baseURL = baseURL
    }

    public func fetchServers() async throws -> [Server] {
        let url = baseURL.appendingPathComponent("api/v1/servers")
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw CatalogClientError.invalidResponse
        }
        guard (200..<300).contains(http.statusCode) else {
            throw CatalogClientError.httpStatus(http.statusCode)
        }

        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let result = try decoder.decode(ServerListResponse.self, from: data)
        return result.data
    }
}
