package com.novel.system.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateProjectRequest {

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 255, message = "项目名称不能超过255字符")
    private String name;

    @Size(max = 1000, message = "项目描述不能超过1000字符")
    private String description;

    @Size(max = 120, message = "genre must not exceed 120 characters")
    private String genre;

    @NotBlank(message = "样本类型不能为空")
    @Pattern(regexp = "SAME_AUTHOR|SAME_GENRE|MIXED", message = "样本类型无效")
    private String sampleGroupType;

    private String sourceLanguage = "zh-CN";

    private String targetLanguage = "zh-CN";
}
