package com.project.backend.data;

import static java.util.Objects.requireNonNull;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import com.project.backend.mcp.tool.ToolDataCatalog;
import com.project.backend.warranty.WarrantyCatalog;

/** A data file that differs from source-manifest.json by even one byte must stop the application from starting. */
class DataIntegrityTests {
    static final List<String> FILES=List.of(DataManifest.MANIFEST,"homecare/knowledge.json","homecare/models.json","homecare/parts.json",
            "homecare/centers.json","homecare/route.json","homecare/approved-plans.json","homecare/repair-cases.json","homecare/knowledge-structured.json","homecare/symptom-parts.json","homecare/error-guides.json","warranty/policies.json");
    @TempDir Path root;

    @BeforeEach void copyPackagedData() throws Exception {
        for(var file:FILES){
            var target=root.resolve(file);Files.createDirectories(target.getParent());
            try(var in=new ClassPathResource(requireNonNull(file)).getInputStream()){Files.copy(in,target);}
        }
    }
    DataManifest manifest(){return new DataManifest(root.toUri().toString(),new DefaultResourceLoader());}
    void tamper(String file) throws Exception {
        var path=root.resolve(file);var bytes=Files.readAllBytes(path);
        bytes[bytes.length/2]^=1;Files.write(path,bytes);
    }

    @Test void untouchedCopyLoadsWithManifestVersion(){
        var catalog=new ToolDataCatalog(manifest());
        assertThat(catalog.version).isEqualTo("B30-COST-V3-20261014-R5");
        assertThat(catalog.parts).hasSize(32);assertThat(catalog.knowledge).hasSize(1542);
        assertThatCode(()->new WarrantyCatalog(manifest())).doesNotThrowAnyException();
    }
    @Test void everyVerifiedFileIsCheckedByteForByte() throws Exception {
        for(var file:FILES.subList(1,FILES.size())){
            tamper(file);
            var error=assertThatThrownBy(()->{var m=manifest();new ToolDataCatalog(m);new WarrantyCatalog(m);});
            error.as(file);error.isInstanceOf(IllegalStateException.class);error.hasMessageContaining("무결성");error.hasMessageContaining(file);
            restore(file);
        }
    }
    @Test void springContextDoesNotStartWithTamperedData() throws Exception {
        tamper("homecare/parts.json");
        new ApplicationContextRunner().withPropertyValues("homecare.data.root="+root.toUri())
                .withUserConfiguration(DataManifest.class,ToolDataCatalog.class,WarrantyCatalog.class)
                .run(context->{
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("homecare/parts.json");
                });
    }
    @Test void manifestCountsMustMatchTheData() throws Exception {
        var path=root.resolve(DataManifest.MANIFEST);
        Files.writeString(path,Files.readString(path).replace("\"costGroups\": 32","\"costGroups\": 33"));
        assertThatThrownBy(()->new ToolDataCatalog(manifest())).hasMessageContaining("건수");
    }
    @Test void projectionMustReferToTheCurrentVerifiedSource() throws Exception {
        var path=root.resolve(DataManifest.MANIFEST);
        var sourceHash=manifest().section("knowledgeProjection").get("sourceSha256").toString();
        Files.writeString(path,Files.readString(path).replace("\"sourceSha256\": \""+sourceHash+"\"","\"sourceSha256\": \"stale\""));
        assertThatThrownBy(()->new ToolDataCatalog(manifest())).hasMessageContaining("원본 해시");
    }

    private void restore(String file) throws Exception {
        try(var in=new ClassPathResource(requireNonNull(file)).getInputStream()){Files.copy(in,root.resolve(file),StandardCopyOption.REPLACE_EXISTING);}
    }
}
