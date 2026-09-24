# Project-specific R8 rules will be added when reflection-based integrations are introduced.
# Commons Compress contains an optional Zstandard stream adapter; ZIP/7z page access never invokes
# it, and zstd-jni is intentionally not shipped (it would add native libraries for four ABIs).
-dontwarn com.github.luben.zstd.ZstdInputStream
