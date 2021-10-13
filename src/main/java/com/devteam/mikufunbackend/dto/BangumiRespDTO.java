package com.devteam.mikufunbackend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class BangumiRespDTO {

    /**
     * 番剧详情
     */
    @JsonProperty("bangumi")
    private BangumiDetailDTO bangumi;
    /**
     * 错误代码，0表示没有发生错误，非0表示有错误，详细信息会包含在errorMessage属性中
     */
    @JsonProperty("errorCode")
    private Integer errorCode;
    /**
     * 接口是否调用成功
     */
    @JsonProperty("success")
    private Boolean success;
    /**
     * 当发生错误时，说明错误具体原因
     */
    @JsonProperty("errorMessage")
    private String errorMessage;

}
