package com.dating.match.config;

import org.springframework.context.annotation.Configuration;

// gRPC 客户端配置占位 ——
//   - 寻址走 application.yml grpc.client.<name>.address: discovery:///<service>
//   - Nacos discovery NameResolver 由 net.devh + spring-cloud-starter-alibaba-nacos-discovery
//     自动注册（GrpcChannelFactory 见到 discovery:// scheme 时挂载 DiscoveryClientNameResolverProvider）
//   - 出站透传当前用户身份见 GrpcClientMetadataInterceptor（如需）
//
// 真到了要配 deadline / 最大消息 / TLS / keep-alive 时再在这里加 @Bean。
@Configuration
public class GrpcClientConfig {
}
