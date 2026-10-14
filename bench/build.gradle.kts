plugins {
    application
    id("me.champeau.jmh")
}

dependencies {
    implementation(project(":core"))
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
    implementation("org.apache.lucene:lucene-core:9.12.0")
    implementation("org.apache.lucene:lucene-analysis-common:9.12.0")
    implementation("org.apache.lucene:lucene-queryparser:9.12.0")
}

application {
    mainClass.set("com.postings.bench.BenchmarkMain")
}

jmh {
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    threads.set(1)
}
