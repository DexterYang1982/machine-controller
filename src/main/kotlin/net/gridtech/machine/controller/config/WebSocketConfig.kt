package net.gridtech.machine.controller.config


import net.gridtech.core.util.KEY_FIELD_SECRET
import net.gridtech.core.util.INSTANCE_ID
import net.gridtech.machine.controller.init.BootService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean


@Configuration
@EnableWebSocket
class WebSocketConfig : WebSocketConfigurer {

    @Autowired
    lateinit var bootService: BootService

    @Bean
    fun createWebSocketContainer(): ServletServerContainerFactoryBean {
        val container = ServletServerContainerFactoryBean()
        container.setMaxTextMessageBufferSize(65535)
        container.setMaxBinaryMessageBufferSize(65535)
        return container
    }

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(bootService.hostMaster, "/master").setAllowedOrigins("*")
                .addInterceptors(object : HandshakeInterceptor {
                    override fun beforeHandshake(request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, attributes: MutableMap<String, Any>): Boolean {
                        val req = request as ServletServerHttpRequest
                        val resp = response as ServletServerHttpResponse
                        val nodeId = req.servletRequest.getParameter("nodeId")
                        val nodeSecret = req.servletRequest.getParameter("nodeSecret")
                        val instance = req.servletRequest.getParameter("instance")
                        if (nodeId == null || nodeSecret == null || instance == null) {
                            resp.setStatusCode(HttpStatus.FORBIDDEN)
                            return false
                        }
                        bootService.bootstrap.fieldValueService.getFieldValueByFieldKey(nodeId, KEY_FIELD_SECRET)
                        val secretFieldValue = bootService.bootstrap.fieldValueService.getFieldValueByFieldKey(nodeId, KEY_FIELD_SECRET)
                        return if (secretFieldValue != null && secretFieldValue.value == nodeSecret) {
                            attributes["nodeId"] = nodeId
                            attributes["instance"] = instance
                            resp.servletResponse.setHeader("instance", INSTANCE_ID)
                            true
                        } else {
                            resp.setStatusCode(HttpStatus.FORBIDDEN)
                            false
                        }
                    }

                    override fun afterHandshake(request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, exception: Exception?) {
                    }
                })
    }
}