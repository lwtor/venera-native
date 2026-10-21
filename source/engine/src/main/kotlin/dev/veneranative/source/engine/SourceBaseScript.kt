package dev.veneranative.source.engine

/**
 * The JavaScript `ComicSource` base class every source extends.
 *
 * Upstream ships this in `assets/init.js`; sources rely on it for their identity fields and for the
 * data/setting helpers. Source data and settings are supplied by the host; source data is encrypted
 * at rest and password-like fields are excluded from exported snapshots.
 *
 * Everything else follows upstream verbatim, including `translate`'s locale lookup and the
 * `sources` registry the host reaches member calls through.
 */
internal object SourceBaseScript {

    val script: String =
        """
        (function () {
          "use strict";

          var data = (function () {
            try {
              var restored = JSON.parse(globalThis.__veneraSourceDataJson || "{}");
              return restored && typeof restored === "object" && !Array.isArray(restored) ? restored : {};
            } catch (_) { return {}; }
          })();

          // Upstream source scripts construct search/explore results with `new Comic({...})`.
          // This is a plain value object (no host capabilities); keep its fields aligned with the
          // constructor in venera-configs/_venera_.js so JSON serialization exposes the same data.
          function Comic({id, title, subtitle, subTitle, cover, tags, description, maxPage, language, favoriteId, stars}) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.subTitle = subTitle;
            this.cover = cover;
            this.tags = tags;
            this.description = description;
            this.maxPage = maxPage;
            this.language = language;
            this.favoriteId = favoriteId;
            this.stars = stars;
          }

          // Detail sources such as JM return `new ComicDetails({...})`. Keep this constructor a
          // plain value object, matching the upstream host contract so QuickJS can serialize it.
          function ComicDetails({title, subtitle, subTitle, cover, description, tags, chapters, isFavorite, subId, thumbnails, recommend, commentCount, likesCount, isLiked, uploader, updateTime, uploadTime, url, stars, maxPage, comments}) {
            this.title = title;
            this.subtitle = subtitle == null ? subTitle : subtitle;
            this.cover = cover;
            this.description = description;
            this.tags = tags;
            this.chapters = chapters;
            this.isFavorite = isFavorite;
            this.subId = subId;
            this.thumbnails = thumbnails;
            this.recommend = recommend;
            this.commentCount = commentCount;
            this.likesCount = likesCount;
            this.isLiked = isLiked;
            this.uploader = uploader;
            this.updateTime = updateTime;
            this.uploadTime = uploadTime;
            this.url = url;
            this.stars = stars;
            this.maxPage = maxPage;
            this.comments = comments;
          }

          function Comment({userName, avatar, content, time, replyCount, id, isLiked, score, voteStatus}) {
            this.userName = userName;
            this.avatar = avatar;
            this.content = content;
            this.time = time;
            this.replyCount = replyCount;
            this.id = id;
            this.isLiked = isLiked;
            this.score = score;
            this.voteStatus = voteStatus;
          }

          class ComicSource {
            constructor() {
              this.name = "";
              this.key = "";
              this.version = "";
              this.minAppVersion = "";
              this.url = "";
              this.translation = {};

              // Several source scripts declare fallbackServers and populate apiDomains during
              // async init(). Keep their baseUrl usable if endpoint discovery fails before it
              // can assign apiDomains.
              var sourceClass = this.constructor;
              if (sourceClass && Array.isArray(sourceClass.fallbackServers) && !Array.isArray(sourceClass.apiDomains)) {
                sourceClass.apiDomains = sourceClass.fallbackServers.slice();
              }
            }

            init() {}

            isAppVersionAfter(version) {
              var currentParts = String(globalThis.APP && globalThis.APP.version || "0").split(".");
              var requiredParts = String(version || "0").split(".");
              var length = Math.max(currentParts.length, requiredParts.length);
              for (var index = 0; index < length; index++) {
                var current = parseInt(currentParts[index] || "0", 10) || 0;
                var required = parseInt(requiredParts[index] || "0", 10) || 0;
                if (current !== required) return current > required;
              }
              return false;
            }

            loadData(dataKey) {
              return Object.prototype.hasOwnProperty.call(data, dataKey) ? data[dataKey] : null;
            }

            saveData(dataKey, value) {
              data[dataKey] = value;
            }

            deleteData(dataKey) {
              delete data[dataKey];
            }

            __veneraExportData() {
              try {
                return JSON.parse(JSON.stringify(data, function (key, value) {
                  return /(?:password|passwd|pwd)/i.test(key) ? undefined : value;
                }));
              } catch (_) {
                return {};
              }
            }

            loadSetting(key) {
              try {
                var overrides = JSON.parse(globalThis.__veneraSettingsJson || "{}");
                if (Object.prototype.hasOwnProperty.call(overrides, key)) return overrides[key];
              } catch (_) {}
              var settings = this.settings;
              if (settings && typeof settings === "object") {
                var declared = settings[key];
                if (declared && typeof declared === "object" && declared.default !== undefined) {
                  return declared.default;
                }
                // Some upstream sources replace a setting declaration at runtime (for example,
                // CopyManga writes its current API host into `settings.base_url` during init).
                // Preserve that live scalar value when `loadSetting` is read later.
                if (typeof declared === "string" || typeof declared === "number" || typeof declared === "boolean") {
                  return declared;
                }
              }
              return null;
            }

            get isLogged() {
              return !!(data.token || data.account);
            }

            __veneraIsLogged() { return !!this.isLogged; }

            translate(key) {
              var app = globalThis.APP;
              var locale = app && typeof app.locale === "string" ? app.locale : "";
              var table = this.translation ? this.translation[locale] : undefined;
              var value = table ? table[key] : undefined;
              return value === undefined || value === null ? key : value;
            }
          }

          ComicSource.sources = {};
          globalThis.Comic = Comic;
          globalThis.ComicDetails = ComicDetails;
          globalThis.Comment = Comment;
          globalThis.ComicSource = ComicSource;
        })();
        """.trimIndent()
}
