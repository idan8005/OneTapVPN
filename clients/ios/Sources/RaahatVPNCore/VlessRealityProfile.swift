import Foundation
public enum VlessProfileError:Error{case malformed,invalidUUID,unsupportedSecurity,missingRealityFields,unsupportedTransport}
public struct VlessRealityProfile{
 public let uuid:String,host:String,sni:String,fingerprint:String,publicKey:String,shortID:String,transport:String,path:String,hostHeader:String,grpcServiceName:String,flow:String
 public let port:Int
 public init(link:String)throws{
  guard let c=URLComponents(string:link.trimmingCharacters(in:.whitespacesAndNewlines)),c.scheme?.lowercased()=="vless",let host=c.host,let user=c.user,let uuid=user.removingPercentEncoding,UUID(uuidString:uuid) != nil else{throw VlessProfileError.malformed}
  let q=Dictionary(c.queryItems?.map{($0.name.lowercased(),$0.value ?? "")} ?? [],uniquingKeysWith:{_,latest in latest})
  guard q["security"]?.lowercased()=="reality" else{throw VlessProfileError.unsupportedSecurity}
  let t=q["type"]?.lowercased().nonEmpty ?? "tcp"
  guard ["tcp","raw","ws","grpc","http","httpupgrade"].contains(t) else{throw VlessProfileError.unsupportedTransport}
  let pbk=q["pbk"]?.nonEmpty ?? q["publickey"]?.nonEmpty ?? "",sid=q["sid"] ?? q["shortid"] ?? "",flow=q["flow"] ?? ""
  guard !pbk.isEmpty,sid.range(of:"^[0-9a-fA-F]{0,16}$",options:.regularExpression) != nil else{throw VlessProfileError.missingRealityFields}
  guard flow.isEmpty || (flow=="xtls-rprx-vision" && ["tcp","raw"].contains(t)) else{throw VlessProfileError.unsupportedTransport}
  self.uuid=uuid;self.host=host;self.port=c.port ?? 443
  self.sni=q["sni"]?.nonEmpty ?? q["servername"]?.nonEmpty ?? host
  self.fingerprint=q["fp"]?.nonEmpty ?? "chrome";self.publicKey=pbk;self.shortID=sid;self.transport=t
  self.path=q["path"]?.nonEmpty ?? "/";self.hostHeader=q["host"]?.nonEmpty ?? ""
  self.grpcServiceName=q["servicename"]?.nonEmpty ?? q["service_name"]?.nonEmpty ?? q["service"]?.nonEmpty ?? ""
  self.flow=flow
 }
 public func makeSingBoxConfig()throws->Data{
  if transport=="grpc" && grpcServiceName.isEmpty{throw VlessProfileError.missingRealityFields}
  var out:[String:Any]=["type":"vless","tag":"proxy","server":host,"server_port":port,"uuid":uuid,"domain_resolver":"dns-local",
   "tls":["enabled":true,"server_name":sni,"utls":["enabled":true,"fingerprint":fingerprint],"reality":["enabled":true,"public_key":publicKey,"short_id":shortID]]]
  if !flow.isEmpty{out["flow"]=flow}
  if transport=="ws"{var t:[String:Any]=["type":"ws","path":path];if !hostHeader.isEmpty{t["headers"]=["Host":hostHeader]};out["transport"]=t}
  else if transport=="grpc"{out["transport"]=["type":"grpc","service_name":grpcServiceName]}
  else if transport=="http"{var t:[String:Any]=["type":"http","path":path];if !hostHeader.isEmpty{t["host"]=hostHeader.split(separator:",").map(String.init)};out["transport"]=t}
  else if transport=="httpupgrade"{var t:[String:Any]=["type":"httpupgrade","path":path];if !hostHeader.isEmpty{t["host"]=hostHeader};out["transport"]=t}
  let config:[String:Any]=[
   "log":["level":"info","timestamp":true],
   "dns":["servers":[["type":"local","tag":"dns-local"]],"final":"dns-local","strategy":"prefer_ipv4"],
   "inbounds":[["type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"mtu":1400,"auto_route":true,"strict_route":true,"dns_mode":"hijack"]],
   "outbounds":[out,["type":"direct","tag":"direct"]],
   "route":["rules":[["protocol":"dns","action":"hijack-dns"]],"auto_detect_interface":true,"final":"proxy"]
  ]
  return try JSONSerialization.data(withJSONObject:config,options:[.prettyPrinted,.sortedKeys])
 }
}
private extension String{var nonEmpty:String?{isEmpty ? nil:self}}
