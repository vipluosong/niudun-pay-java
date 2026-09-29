package cn.niudun.open.sdk.net;

import cn.niudun.open.sdk.param.AllocParam;
import cn.niudun.open.sdk.param.AllocQueryParam;
import cn.niudun.open.sdk.param.AllocSyncParam;
import cn.niudun.open.sdk.param.CloseParam;
import cn.niudun.open.sdk.param.GatewayOrderQueryParam;
import cn.niudun.open.sdk.param.GatewayPrePayParam;
import cn.niudun.open.sdk.param.PayParam;
import cn.niudun.open.sdk.param.PayQueryParam;
import cn.niudun.open.sdk.param.PingParam;
import cn.niudun.open.sdk.param.PaySyncParam;
import cn.niudun.open.sdk.param.RefundParam;
import cn.niudun.open.sdk.param.RefundQueryParam;
import cn.niudun.open.sdk.param.RefundSyncParam;
import cn.niudun.open.sdk.param.TransferParam;
import cn.niudun.open.sdk.param.TransferQueryParam;
import cn.niudun.open.sdk.param.TransferSyncParam;
import cn.niudun.open.sdk.response.NiuDunResult;
import cn.niudun.open.sdk.result.AllocOrderResult;
import cn.niudun.open.sdk.result.AllocResult;
import cn.niudun.open.sdk.result.AllocSyncResult;
import cn.niudun.open.sdk.result.GatewayOrderResult;
import cn.niudun.open.sdk.result.GatewayPrePayResult;
import cn.niudun.open.sdk.result.NormalPayResult;
import cn.niudun.open.sdk.result.PayOrderResult;
import cn.niudun.open.sdk.result.PaySyncResult;
import cn.niudun.open.sdk.result.PingResult;
import cn.niudun.open.sdk.result.RefundOrderResult;
import cn.niudun.open.sdk.result.RefundResult;
import cn.niudun.open.sdk.result.RefundSyncResult;
import cn.niudun.open.sdk.result.TransferOrderResult;
import cn.niudun.open.sdk.result.TransferResult;
import cn.niudun.open.sdk.result.TransferSyncResult;
import cn.niudun.open.sdk.util.PaySignUtil;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.HttpStatus;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.Objects;

/// # 牛盾支付 SDK 客户端
///
/// 对照 sdk-contract.md 第十节。走 JSON 签名路径（reqTime 序列化为 GMT+8 字面量），与后端验签一致。
public class NiuDunClient {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NiuDunConfig config;

    /// 调用观测器（可空，联调 demo 等场景挂载）
    private NiuDunObserver observer;

    public NiuDunClient(NiuDunConfig config) {
        this.config = config;
    }

    /// 设置调用观测器（拿到签名后请求体与原始响应体，联调排障用）
    public NiuDunClient setObserver(NiuDunObserver observer) {
        this.observer = observer;
        return this;
    }

    /// 通用执行：自动填充公共参数 → JSON 签名 → POST → 验签 → 按 dataClass 解析 data
    public <T> NiuDunResult<T> execute(String path, Object param, Class<T> dataClass) {
        return execute(path, param, dataClass, true);
    }

    /// execute 的完整形态：throwOnBizError=false 时非 0 业务码不抛异常而是原样返回 NiuDunResult
    /// （签名自检探针的职责是报告检查结果，失败码/失败消息本身就是有效答案）；
    /// 响应验签失败仍抛异常（那是平台公钥配置问题，属于硬错误而非探针答案）。
    public <T> NiuDunResult<T> execute(String path, Object param, Class<T> dataClass, boolean throwOnBizError) {
        // param → JSONObject（保留字段），注入公共字段
        JSONObject json = JSONUtil.parseObj(JSONUtil.toJsonStr(param));
        json.putIfAbsent("mchNo", config.getMchNo());
        // appId 空串视为未配置（回落平台默认应用）
        if (StrUtil.isNotBlank(config.getAppId())) {
            json.putIfAbsent("appId", config.getAppId());
        }
        json.putIfAbsent("reqId", UUID.randomUUID().toString());
        json.putIfAbsent("reqTime", nowGmt8());
        json.putIfAbsent("nonceStr", UUID.randomUUID().toString().replace("-", ""));

        // 走 JSON 签名路径：对序列化后的 JSON 签名 → 注入 sign → 重新序列化发送
        String jsonForSign = json.toString();
        String sign = PaySignUtil.sign(jsonForSign, config.getPrivateKey());
        json.put("sign", sign);
        String body = json.toString();
        if (Objects.nonNull(observer)) {
            observer.onRequest(body);
        }

        // POST
        String url = config.getServiceUrl() + path;
        String responseBody;
        try (HttpResponse resp = HttpUtil.createPost(url)
                .body(body, ContentType.JSON.getValue())
                .timeout(config.getReqTimeout())
                .execute()) {
            if (resp.getStatus() != HttpStatus.HTTP_OK) {
                throw new RuntimeException("请求失败: HTTP " + resp.getStatus());
            }
            responseBody = resp.body();
        }
        if (Objects.nonNull(observer)) {
            observer.onResponse(responseBody);
        }

        // 验签策略：带 sign 的响应强制校验；成功响应必须带签名；失败响应允许无签名（兼容旧版平台）。
        // 平台自 2026-09-21 起已统一：验签阶段失败（商户不存在/验签失败/防重放等）与控制器内业务异常
        // 都返回带签名的 NiuDunResult，失败响应与成功响应同形。无签名的失败响应只会来自旧版平台
        // （其全局异常处理器返回管理 API 的 Result 形状：code/message，无 sign），
        // 此处保留兼容读取，避免旧版平台下真实错误码与消息全部丢失。
        JSONObject respJson = JSONUtil.parseObj(responseBody);
        boolean signed = StrUtil.isNotBlank(respJson.getStr("sign"));
        if (signed) {
            if (!PaySignUtil.verify(responseBody, config.getPublicKey())) {
                throw new RuntimeException("响应验签失败");
            }
        } else if (respJson.getInt("code", -1) == 0) {
            // 成功响应必须带签名，否则来源不可信（防伪造成功响应）
            throw new RuntimeException("响应缺少签名，无法验证来源");
        }

        // 解析 + 业务码检查
        NiuDunResult<JSONObject> raw = JSONUtil.toBean(responseBody,
                new TypeReference<NiuDunResult<JSONObject>>() {
                }, true);
        // 兼容旧版平台异常响应的 message 字段（NiuDunResult 为 msg）
        if (StrUtil.isBlank(raw.getMsg())) {
            raw.setMsg(respJson.getStr("message"));
        }
        if (raw.getCode() != 0) {
            if (throwOnBizError) {
                throw new RuntimeException("[" + raw.getCode() + "] " + raw.getMsg());
            }
            // 非 0 码时 data 必为 null，直接组装返回（探针诊断路径）
            NiuDunResult<T> errResult = new NiuDunResult<>();
            errResult.setCode(raw.getCode()).setMsg(raw.getMsg())
                    .setSign(raw.getSign()).setResTime(raw.getResTime()).setReqId(raw.getReqId());
            return errResult;
        }
        T data = null;
        if (Objects.nonNull(raw.getData())) {
            data = raw.getData().toBean(dataClass);
        }
        NiuDunResult<T> result = new NiuDunResult<>();
        result.setCode(raw.getCode()).setMsg(raw.getMsg()).setData(data)
                .setSign(raw.getSign()).setResTime(raw.getResTime()).setReqId(raw.getReqId());
        return result;
    }

    /// 支付下单便捷方法 — POST /unipay/pay
    public NiuDunResult<NormalPayResult> pay(PayParam param) {
        return execute("/unipay/pay", param, NormalPayResult.class);
    }

    /// 关闭/撤销订单便捷方法 — POST /unipay/close（响应 data 为 null，凭 code==0 判断成功）
    public NiuDunResult<Void> close(CloseParam param) {
        return execute("/unipay/close", param, Void.class);
    }

    /// 退款便捷方法 — POST /unipay/refund
    public NiuDunResult<RefundResult> refund(RefundParam param) {
        return execute("/unipay/refund", param, RefundResult.class);
    }

    /// 查询支付订单便捷方法 — POST /unipay/query/pay-order
    public NiuDunResult<PayOrderResult> queryPayOrder(PayQueryParam param) {
        return execute("/unipay/query/pay-order", param, PayOrderResult.class);
    }

    /// 查询退款订单便捷方法 — POST /unipay/query/refund-order
    public NiuDunResult<RefundOrderResult> queryRefundOrder(RefundQueryParam param) {
        return execute("/unipay/query/refund-order", param, RefundOrderResult.class);
    }

    /// 转账便捷方法 — POST /unipay/transfer（通道直连转账，幂等维度：通道+商户转账号+商户号）
    public NiuDunResult<TransferResult> transfer(TransferParam param) {
        return execute("/unipay/transfer", param, TransferResult.class);
    }

    /// 分账便捷方法 — POST /unipay/alloc（原支付单须下单时声明 allocation=true）
    public NiuDunResult<AllocResult> alloc(AllocParam param) {
        return execute("/unipay/alloc", param, AllocResult.class);
    }

    /// 查询分账订单便捷方法 — POST /unipay/query/alloc-order（仅查本地单，不调通道）
    public NiuDunResult<AllocOrderResult> queryAllocOrder(AllocQueryParam param) {
        return execute("/unipay/query/alloc-order", param, AllocOrderResult.class);
    }

    /// 查询转账订单便捷方法 — POST /unipay/query/transfer-order（仅查本地单，不调通道）
    public NiuDunResult<TransferOrderResult> queryTransferOrder(TransferQueryParam param) {
        return execute("/unipay/query/transfer-order", param, TransferOrderResult.class);
    }

    /// 支付订单同步 — POST /unipay/sync/order/pay（主动拉通道最新状态并回写本地，回调丢失的兜底补偿）
    public NiuDunResult<PaySyncResult> syncPayOrder(PaySyncParam param) {
        return execute("/unipay/sync/order/pay", param, PaySyncResult.class);
    }

    /// 退款订单同步 — POST /unipay/sync/order/refund
    public NiuDunResult<RefundSyncResult> syncRefundOrder(RefundSyncParam param) {
        return execute("/unipay/sync/order/refund", param, RefundSyncResult.class);
    }

    /// 分账订单同步 — POST /unipay/sync/order/alloc
    public NiuDunResult<AllocSyncResult> syncAllocOrder(AllocSyncParam param) {
        return execute("/unipay/sync/order/alloc", param, AllocSyncResult.class);
    }

    /// 转账订单同步 — POST /unipay/sync/order/transfer
    public NiuDunResult<TransferSyncResult> syncTransferOrder(TransferSyncParam param) {
        return execute("/unipay/sync/order/transfer", param, TransferSyncResult.class);
    }

    /// 网关预下单 — POST /unipay/gateway/pre-pay（返回收银台跳转地址 h5Url/miniUrl）
    public NiuDunResult<GatewayPrePayResult> gatewayPrePay(GatewayPrePayParam param) {
        return execute("/unipay/gateway/pre-pay", param, GatewayPrePayResult.class);
    }

    /// 网关订单查询 — POST /unipay/gateway/query
    public NiuDunResult<GatewayOrderResult> gatewayQuery(GatewayOrderQueryParam param) {
        return execute("/unipay/gateway/query", param, GatewayOrderResult.class);
    }

    /// 签名自检探针 — POST /unipay/ping（走完整验签链路，一键判定商户号/应用/私钥/签名串是否可用）
    ///
    /// 与免签名 [#ping] 互补：本方法由持商户私钥方发起，非 0 业务码不抛异常而是原样返回，
    /// 供调用方按 code 分类诊断（20052=验签失败且 msg 含服务端待签串；10408-10411=nonce/时钟；
    /// 其余=商户号/应用类）；响应验签失败仍抛异常（平台公钥配置问题）。
    public NiuDunResult<PingResult> signedPing(PingParam param) {
        return execute("/unipay/ping", param, PingResult.class, false);
    }

    /// 回调链路自检探针 — GET /unipay/callback/ping（免签名免登录，返回固定标识文本）
    ///
    /// 用于部署自检：探针可达即代表「通道回调」接口组已放行、后端地址配置正确。
    public String ping() {
        try (HttpResponse resp = HttpUtil.createGet(config.getServiceUrl() + "/unipay/callback/ping")
                .timeout(config.getReqTimeout())
                .execute()) {
            if (resp.getStatus() != HttpStatus.HTTP_OK) {
                throw new RuntimeException("探针请求失败: HTTP " + resp.getStatus());
            }
            return resp.body();
        }
    }

    /// 回调验签（原始 HTTP body 字符串）— 对照契约第八节
    public boolean verifyNotice(String rawBody) {
        return PaySignUtil.verify(rawBody, config.getPublicKey());
    }

    private String nowGmt8() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8)).format(FMT);
    }
}
