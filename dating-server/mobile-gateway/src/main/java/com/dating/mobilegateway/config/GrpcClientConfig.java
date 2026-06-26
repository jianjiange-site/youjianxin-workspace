package com.dating.mobilegateway.config;

import org.springframework.context.annotation.Configuration;

// gRPC 客户端配置占位 ——
//   - 寻址走 application.yml `grpc.client.user-service.address: discovery:///user-service`
//   - Nacos discovery NameResolver 由 net.devh + spring-cloud-starter-alibaba-nacos-discovery
//     自动注册(GrpcChannelFactory 见到 discovery:// scheme 时挂载 DiscoveryClientNameResolverProvider)
//   - 全局 ClientInterceptor 见 GrpcClientMetadataInterceptor (@GrpcGlobalClientInterceptor 自动装载)
//
// 真到了要调 deadline / 最大消息 / TLS / keep-alive 时再在这里加 @Bean。
@Configuration
public class GrpcClientConfig {
}
