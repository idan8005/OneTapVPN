import Foundation

public struct HybridServer: Codable, Sendable, Identifiable {
 public let id:String
 public let name:String
 public let country:String
 public let region:String
 public let host:String
 public let port:Int
 public let protocolName:String
 public let config:String
 public let enabled:Bool
 enum CodingKeys:String,CodingKey { case id,name,country,region,host,port,config,enabled; case protocolName="protocol" }
}
public struct HybridCatalog:Codable,Sendable {
 public let plan:String
 public let generatedAt:String
 public let publicDiscovery:Bool
 public let premiumServers:[HybridServer]
}
public enum HybridClientError:Error { case invalidEndpoint,invalidToken,unauthorized,forbidden,unavailable,httpStatus(Int),invalidResponse }
private final class NoRedirect:NSObject,URLSessionTaskDelegate {
 func urlSession(_ session:URLSession,task:URLSessionTask,willPerformHTTPRedirection response:HTTPURLResponse,newRequest request:URLRequest,completionHandler:@escaping(URLRequest?)->Void){completionHandler(nil)}
}
public struct HybridCatalogClient {
 private let baseURL:URL
 public init(baseURL:URL)throws{
  guard baseURL.scheme?.lowercased()=="https",baseURL.host != nil,baseURL.user==nil,baseURL.password==nil,baseURL.query==nil,baseURL.fragment==nil else{throw HybridClientError.invalidEndpoint}
  self.baseURL=baseURL
 }
 public func fetchCatalog(accessToken:String)async throws->HybridCatalog{
  let token=accessToken.trimmingCharacters(in:.whitespacesAndNewlines)
  guard !token.isEmpty,token.count<=4096 else{throw HybridClientError.invalidToken}
  let url=baseURL.appendingPathComponent("api/v1/catalog")
  var req=URLRequest(url:url,timeoutInterval:15);req.httpMethod="GET"
  req.setValue("Bearer \(token)",forHTTPHeaderField:"Authorization")
  req.setValue("application/json",forHTTPHeaderField:"Accept")
  req.setValue("RaahatVPN-iOS/1.0",forHTTPHeaderField:"User-Agent")
  let session=URLSession(configuration:.ephemeral,delegate:NoRedirect(),delegateQueue:nil)
  defer{session.invalidateAndCancel()}
  let(data,response)=try await session.data(for:req)
  guard let http=response as? HTTPURLResponse else{throw HybridClientError.invalidResponse}
  switch http.statusCode{case 200:break;case 401:throw HybridClientError.unauthorized;case 403:throw HybridClientError.forbidden;case 503:throw HybridClientError.unavailable;default:throw HybridClientError.httpStatus(http.statusCode)}
  let catalog=try JSONDecoder().decode(HybridCatalog.self,from:data)
  guard catalog.plan=="free"||catalog.plan=="premium" else{throw HybridClientError.invalidResponse}
  let nodes = catalog.premiumServers.filter { $0.enabled && $0.protocolName == "vless-reality" && !$0.config.isEmpty && (1...65535).contains($0.port) }.prefix(60)
  return HybridCatalog(plan:catalog.plan,generatedAt:catalog.generatedAt,publicDiscovery:catalog.publicDiscovery,premiumServers:Array(nodes))
 }
}
