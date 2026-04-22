plugins {
    application
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.empire.hasher.Main")
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "com.empire.hasher.Main"
    }
}
