package io.github.asagnc.sta.agent.browser

import org.json.JSONObject

/**
 * 文档起始阶段注入的请求头脚本。
 *
 * `loadUrl` 的附加请求头只覆盖主文档，页面自己发出的 XHR/fetch 拿不到；`addDocumentStartJavaScript`
 * 可以在文档开始时就注入脚本，这里用它包装 fetch 与 XMLHttpRequest。
 *
 * 安全边界：只有请求目标与注入作用域同源时才加头，避免把带凭据的头泄漏给第三方站点。
 */
internal object BrowserRequestHeaderScript {
    fun build(headers: Map<String, String>, origin: String): String {
        val headerPairs = headers.entries.joinToString(", ") { (name, value) ->
            "${JSONObject.quote(name)}: ${JSONObject.quote(value)}"
        }
        return """
        (function() {
          var etaHeaders = { $headerPairs };
          var etaOrigin = ${JSONObject.quote(origin)};
          function etaSameOrigin(url) {
            try {
              return new URL(url, document.baseURI).origin === etaOrigin;
            } catch (error) {
              return false;
            }
          }
          var etaFetch = window.fetch;
          if (typeof etaFetch === 'function') {
            window.fetch = function(input, init) {
              var url = typeof input === 'string' ? input : (input && input.url) || '';
              if (!etaSameOrigin(url)) return etaFetch.call(this, input, init);
              init = init || {};
              var headers = new Headers(init.headers || (input && input.headers) || undefined);
              for (var name in etaHeaders) headers.set(name, etaHeaders[name]);
              init.headers = headers;
              return etaFetch.call(this, input, init);
            };
          }
          var etaOpen = XMLHttpRequest.prototype.open;
          var etaSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(method, url) {
            this.__etaSameOrigin = etaSameOrigin(url);
            return etaOpen.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function() {
            if (this.__etaSameOrigin) {
              for (var name in etaHeaders) {
                try {
                  this.setRequestHeader(name, etaHeaders[name]);
                } catch (error) {
                }
              }
            }
            return etaSend.apply(this, arguments);
          };
        })();
        """.trimIndent()
    }
}
