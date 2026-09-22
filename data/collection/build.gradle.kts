plugins { id("venera.android.library") }

android {
    namespace = "dev.veneranative.data.collection"
}

dependencies {
    // Domain models appear in the repository's public signatures, so they are exposed to callers.
    api(project(":core:model"))
    implementation(project(":core:database"))
    // Only for `ComicCatalogChapterProbe`: the shelf asks installed sources for a chapter snapshot
    // through the same catalog the details screen uses, so no second protocol path exists.
    implementation(project(":data:comic"))
    // `ComicCatalog` answers with `SourceOutcome`, a `:source:api` type, so reading that answer
    // needs the contract module on the compile classpath too.
    implementation(project(":source:api"))

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
