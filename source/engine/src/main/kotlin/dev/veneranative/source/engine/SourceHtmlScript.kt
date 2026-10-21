package dev.veneranative.source.engine

/** Synchronous JavaScript facade matching the source ecosystem's HtmlDocument value API. */
internal object SourceHtmlScript {
    val script = """
        (function() {
          "use strict";
          var nativeHtmlApi = globalThis.__veneraHtmlApi;
          try { delete globalThis.__veneraHtmlApi; } catch (_) {}
          if (typeof nativeHtmlApi !== "function") throw new Error("HTML parser is not installed.");

          function htmlCall(action, values, invocationId) {
            var request = Object.assign({}, values || {}, {
              action: action,
              invocationId: invocationId || globalThis.__veneraInvocationId || "source-install"
            });
            var result = JSON.parse(nativeHtmlApi(JSON.stringify(request)));
            if (result && result.error) throw new Error(result.error);
            return result;
          }

          function createElement(documentId, elementId) {
            if (elementId == null) return null;
            var element = {};
            element.querySelector = function(selector) {
              var id = htmlCall("query", {
                documentId: documentId, elementId: elementId, selector: String(selector)
              }).elementId;
              return createElement(documentId, id);
            };
            element.querySelectorAll = function(selector) {
              return htmlCall("queryAll", {
                documentId: documentId, elementId: elementId, selector: String(selector)
              }).elementIds.map(function(id) { return createElement(documentId, id); });
            };
            element.getElementById = function(id) {
              var found = htmlCall("getElementById", {
                documentId: documentId, elementId: elementId, id: String(id)
              }).elementId;
              return createElement(documentId, found);
            };
            Object.defineProperties(element, {
              text: { enumerable: true, get: function() { return htmlCall("text", { elementId: elementId }).value; } },
              attributes: { enumerable: true, get: function() { return htmlCall("attributes", { elementId: elementId }); } },
              children: { enumerable: true, get: function() {
                return htmlCall("children", { elementId: elementId }).elementIds.map(function(id) { return createElement(documentId, id); });
              } },
              parent: { enumerable: true, get: function() {
                return createElement(documentId, htmlCall("parent", { elementId: elementId }).elementId);
              } },
              innerHtml: { enumerable: true, get: function() { return htmlCall("innerHtml", { elementId: elementId }).value; } },
              classNames: { enumerable: true, get: function() { return htmlCall("classNames", { elementId: elementId }).value; } },
              id: { enumerable: true, get: function() { return htmlCall("id", { elementId: elementId }).value; } },
              localName: { enumerable: true, get: function() { return htmlCall("localName", { elementId: elementId }).value; } },
              previousSibling: { enumerable: true, get: function() {
                return createElement(documentId, htmlCall("previousSibling", { elementId: elementId }).elementId);
              } },
              nextSibling: { enumerable: true, get: function() {
                return createElement(documentId, htmlCall("nextSibling", { elementId: elementId }).elementId);
              } }
            });
            return element;
          }

          globalThis.HtmlDocument = function(html) {
            var documentId = htmlCall("create", { html: String(html == null ? "" : html) }).documentId;
            this.querySelector = function(selector) {
              var id = htmlCall("query", { documentId: documentId, selector: String(selector) }).elementId;
              return createElement(documentId, id);
            };
            this.querySelectorAll = function(selector) {
              return htmlCall("queryAll", { documentId: documentId, selector: String(selector) }).elementIds
                .map(function(id) { return createElement(documentId, id); });
            };
            this.getElementById = function(id) {
              var found = htmlCall("getElementById", { documentId: documentId, id: String(id) }).elementId;
              return createElement(documentId, found);
            };
            this.dispose = function() { htmlCall("dispose", {}); };
          };
          globalThis.__veneraDisposeHtmlInvocation = function(invocationId) {
            htmlCall("dispose", {}, String(invocationId));
          };
        })();
    """.trimIndent()
}
