package dev.veneranative.source.api.protocol

/** Preserve fields discarded by CopyManga/Komiic list adapters, using the same HTTP response. */
object ListMetadataCompatibility {
    val script = """
        globalThis.__veneraListMetadata = (function() {
          var invocation = null, records = Object.create(null);
          function allowed() {
            var source = globalThis.__veneraSource;
            return source && (source.key === 'copy_manga' || source.key === 'Komiic');
          }
          return Object.freeze({
            capture: function(body, callId) {
              if (!allowed() || typeof body !== 'string' || body.length > 1048576) return;
              var current = globalThis.__veneraInvocationId;
              if (typeof current !== 'string' || callId !== current) return;
              if (current !== invocation) { records = Object.create(null); invocation = current; }
              var count = 0;
              function visit(value, depth) {
                if (!value || typeof value !== 'object' || depth > 8 || count >= 100) return;
                var id = value.path_word || value.id;
                var authors = value.author || value.authors;
                if (id != null && (value.title || value.name) && Array.isArray(authors)) {
                  var names = authors.map(function(author) { return typeof author === 'string' ? author : author && author.name; })
                    .filter(function(name) { return typeof name === 'string' && name.trim(); });
                  var status = value.status;
                  if (status && typeof status === 'object') status = status.display || status.name;
                  records[String(id)] = {authors: names, status: status, lastUpdated: value.datetime_updated || value.dateUpdated};
                  count++;
                }
                Object.keys(value).slice(0, 100).forEach(function(key) { visit(value[key], depth + 1); });
              }
              try { visit(JSON.parse(body), 0); } catch (_) {}
            },
            clear: function(callId) {
              if (callId === invocation) { records = Object.create(null); invocation = null; }
            },
            enrich: function(value) {
              if (!allowed() || invocation !== globalThis.__veneraInvocationId || !value ||
                  typeof value !== 'object' || Array.isArray(value) || value.id == null || !value.title) return value;
              var metadata = records[String(value.id)];
              if (!metadata) return value;
              var enriched = Object.assign({}, value);
              if (!Array.isArray(enriched.authors) || !enriched.authors.length) enriched.authors = metadata.authors;
              if (enriched.status == null) enriched.status = metadata.status;
              if (enriched.lastUpdated == null && enriched.updateTime == null && enriched.dateUpdated == null) {
                enriched.lastUpdated = metadata.lastUpdated;
              }
              return enriched;
            }
          });
        })();
    """.trimIndent()
}
