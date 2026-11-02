package com.easysavevideo.verification.local

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class FrozenRecoveryComponentTest {
    @Test fun authored80MutationsActualJsoupComponent() {
        val directory=System.getenv("ESV_OWNED_CORPUS");org.junit.Assume.assumeNotNull(directory)
        val root=Path.of(directory!!).resolve("recovery").toRealPath()
        val paths=Files.list(root).use {it.filter {p->p.fileName.toString().endsWith(".html")}.sorted().toList()}
        assertEquals(80,paths.size)
        val parser=LocalHtmlEvidenceParser();val rows=mutableListOf<String>()
        for(path in paths) {
            val bytes=Files.readAllBytes(path);val evidence=parser.parse(bytes,"https://fixture.example.org/recovery/${path.fileName}")
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            rows+="${path.fileName.toString().removeSuffix(".html")}\t${if(evidence!=null)"VIDEO" else "UNKNOWN"}\t$hash"
            if(path.fileName.toString().contains("deleted"))assertNull(evidence)
        }
        val output=Path.of("build/reports/frozen-recovery-b-component.tsv");Files.createDirectories(output.parent)
        Files.writeString(output,"id\tpage_evidence_verdict\tbody_sha256\n"+rows.joinToString("\n")+"\n")
    }
}
