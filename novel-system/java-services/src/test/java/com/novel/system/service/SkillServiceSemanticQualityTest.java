package com.novel.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.system.dto.response.SkillResponse;
import com.novel.system.entity.Project;
import com.novel.system.entity.SkillProfile;
import com.novel.system.repository.SkillProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillServiceSemanticQualityTest {

    private static final String PROJECT_ID = "project_skill_semantic";

    @TempDir
    Path tempDir;

    @Mock
    private ProjectService projectService;

    @Mock
    private SkillProfileRepository skillProfileRepository;

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private SkillService skillService;

    @BeforeEach
    void setUp() throws Exception {
        skillService = new SkillService(projectService, skillProfileRepository);
        ReflectionTestUtils.setField(skillService, "basePath", tempDir.toString());

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("Skill Semantic Test");
        when(projectService.getProject(PROJECT_ID)).thenReturn(project);
        when(skillProfileRepository.findByProjectIdAndName(PROJECT_ID, "default")).thenReturn(Optional.empty());
        when(skillProfileRepository.save(any(SkillProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        writeProjectFile("skills/local/writing_skill.md", semanticWritingSkill());
        writeProjectFile("skills/enabled.yaml", """
            version: 1.0.0
            project_id: project_skill_semantic
            skills:
              - name: writing_skill
                type: writing
                path: skills/local/writing_skill.md
                enabled: true
                priority: 100
                scope:
                  - chapter_writing
                source_samples:
                  - Sample A
            """);
        writeProjectFile("analysis/cross_book/technique_summary.json", "{\"chapter_boundary_rate\":0.91}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateSkillQualityPersistsSemanticQualityDetails() throws Exception {
        Map<String, Object> result = skillService.validateSkillQuality(
            PROJECT_ID,
            "writing_skill",
            Map.of("checkedBy", "unit-test")
        );

        Map<String, Object> semanticQuality = (Map<String, Object>) result.get("semanticQuality");
        assertThat(semanticQuality)
            .containsKeys("score", "status", "dimensions", "risks", "recommendations", "checkedAt");
        assertThat((Integer) semanticQuality.get("score")).isGreaterThanOrEqualTo(80);
        assertThat((List<Map<String, Object>>) semanticQuality.get("dimensions"))
            .extracting(item -> item.get("id"))
            .containsExactly(
                "topic_coverage",
                "rule_specificity",
                "evidence_density",
                "risk_control",
                "maintainability"
            );

        SkillResponse skill = (SkillResponse) result.get("skill");
        assertThat(skill.getSemanticQuality()).containsEntry("status", semanticQuality.get("status"));

        SkillResponse refreshed = skillService.getSkill(PROJECT_ID, "writing_skill");
        assertThat(refreshed.getSemanticQuality())
            .containsEntry("score", semanticQuality.get("score"))
            .containsKey("dimensions");

        String reportPath = String.valueOf(result.get("reportPath"));
        Map<String, Object> report = jsonMapper.readValue(projectRoot().resolve(reportPath).toFile(), Map.class);
        assertThat(report)
            .containsEntry("review_type", "skill_quality_check")
            .containsKey("semantic_quality");

        String enabledYaml = Files.readString(projectRoot().resolve("skills/enabled.yaml"), StandardCharsets.UTF_8);
        assertThat(enabledYaml)
            .contains("semantic_quality:")
            .contains("topic_coverage")
            .contains("latest_quality_report_path");
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateConflictReportPersistsProfileMetadata() throws Exception {
        writeProjectFile("skills/local/backup_skill.md", semanticWritingSkill());
        writeProjectFile("skills/enabled.yaml", """
            version: 1.0.0
            project_id: project_skill_semantic
            skills:
              - name: writing_skill
                type: writing
                path: skills/local/writing_skill.md
                enabled: true
                priority: 100
                scope:
                  - chapter_writing
              - name: backup_skill
                type: writing
                path: skills/local/backup_skill.md
                enabled: true
                priority: 100
                scope:
                  - chapter_writing
            """);

        Map<String, Object> result = skillService.generateConflictReport(
            PROJECT_ID,
            Map.of("checkedBy", "unit-test")
        );

        assertThat(result)
            .containsEntry("projectId", PROJECT_ID)
            .containsKey("reportPath")
            .containsKey("severityCounts");
        assertThat((Integer) result.get("conflictCount")).isGreaterThanOrEqualTo(2);
        assertThat((List<Map<String, Object>>) result.get("conflicts"))
            .allSatisfy(item -> assertThat(item).containsKeys("type", "severity", "skillA", "skillB", "suggestion"));

        String reportPath = String.valueOf(result.get("reportPath"));
        Map<String, Object> report = jsonMapper.readValue(projectRoot().resolve(reportPath).toFile(), Map.class);
        assertThat(report)
            .containsEntry("review_type", "skill_conflict_report")
            .containsEntry("skill_name", "conflicts")
            .containsKey("severity_counts");

        Map<String, Object> profile = (Map<String, Object>) result.get("skillProfile");
        Map<String, Object> metadata = (Map<String, Object>) profile.get("skillMetadata");
        assertThat(metadata)
            .containsEntry("latestConflictReportPath", reportPath)
            .containsKey("latestConflictReport");
        assertThat((Map<String, Object>) metadata.get("latestConflictReport"))
            .containsEntry("path", reportPath)
            .containsEntry("checkedBy", "unit-test")
            .containsKey("severityCounts");
    }

    private String semanticWritingSkill() {
        String body = """
            ---
            title: Writing Skill
            description: Semantic quality fixture
            generated_from: cross_book_synthesis
            source_samples:
              - Sample A
              - Sample B
            evidence:
              - type: frequency
                text: chapter boundary appears in 91% samples
            ---
            # Writing Skill

            ## 用途
            该 Skill 用于 chapter_writing，适用范围是正文写作、风格控制、场景推进和质量验收。

            ## 输入来源
            输入来源必须包含 Project Soul、大纲、章节边界、后续章纲和检索上下文。

            ## 主题覆盖
            必须保持样本形成的文风、语言密度和句式节奏；场景需要有冲突、动作、氛围和推进。
            必须控制节奏快慢，保持张弛，章节边界必须遵守，不得提前透支后续章纲。

            ## 规则
            必须写出可执行检查清单，必须逐项验收质量。应当保持主角行动、情绪和目标一致。
            禁止照搬样本文字，禁止抄袭固定段落，不得复刻原书桥段，避免无证据扩写。
            如遇冲突，按 scope、优先级和人工审批结果处理，记录降级原因。

            ## 证据
            证据来自 Sample A、Sample B、technique_summary.json 和人工引用片段。
            出现率: 91%
            示例:
            ```
            主角先承压，再以行动反击，章末留下下一场景的牵引。
            ```

            ## 语义质量验收
            审查时必须确认风格、场景、节奏、章节边界、后续章纲、证据链、风险控制均可执行。
            质量验收必须输出问题、依据、建议和是否通过。
            """;
        return body + "\n" + "必须 保持 检查清单 验收 证据 示例 禁止 不得 章节边界 后续章纲 风格 场景 节奏。\n".repeat(30);
    }

    private void writeProjectFile(String relativePath, String content) throws Exception {
        Path file = projectRoot().resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private Path projectRoot() {
        return tempDir.resolve("projects").resolve(PROJECT_ID);
    }
}
