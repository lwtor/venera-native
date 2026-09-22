// Protocol-correct local fixture. Serve tools/test-images/out on 127.0.0.1:8765 before use.
class DemoComicSource extends ComicSource {
  constructor() {
    super();
    this.name = "Demo Comic Source";
    this.key = "demo_comic_source";
    this.version = "2.0.0";
    this.url = "http://127.0.0.1:8765";
    const comic = (id) => ({
      id: id,
      title: "Demo Comic " + id,
      cover: this.url + "/page_normal_1080x1440.jpg",
      tags: ["Demo"]
    });
    this.explore = [{
      title: "Demo",
      type: "multiPageComicList",
      load: async (page) => ({ comics: [comic("c" + page)], maxPage: 2 })
    }];
    this.search = {
      load: async (keyword, options, page) => ({
        comics: keyword ? [comic("search-" + page)] : [],
        maxPage: 1
      })
    };
    this.comic = {
      loadInfo: async (id) => ({
        title: "Demo Comic " + id,
        description: "Repository-owned integration fixture.",
        cover: this.url + "/page_normal_1080x1440.jpg",
        chapters: { ch1: "Chapter 1" }
      }),
      loadEp: async () => ({ images: [
        this.url + "/page_normal_1080x1440.jpg",
        this.url + "/page_long_1080x6000.png",
        this.url + "/page_wide_1920x1080.png"
      ] })
    };
  }
}
