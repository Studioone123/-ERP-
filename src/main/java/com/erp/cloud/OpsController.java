package com.erp.cloud;

import com.erp.common.BizException;
import com.erp.common.CacheHub;
import com.erp.common.R;
import com.erp.common.TokenStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时运维端点：一眼看清这次启动到底开了哪些能力。
 *
 * <p>这个 Controller 在默认模式下也存在（所以随时能查），
 * 但它用 {@code ObjectProvider<CloudProbeService>} 做<b>可选注入</b>：
 * cloud profile 未启用时该 Bean 不存在，getIfAvailable() 返回 null，
 * 不会导致启动失败。这是 Spring 里处理「可选依赖」的标准写法。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final TokenStore tokenStore;
    private final CacheHub cacheHub;
    private final Environment env;
    private final ObjectProvider<CloudProbeService> probeProvider;

    public OpsController(TokenStore tokenStore, CacheHub cacheHub, Environment env,
                         ObjectProvider<CloudProbeService> probeProvider) {
        this.tokenStore = tokenStore;
        this.cacheHub = cacheHub;
        this.env = env;
        this.probeProvider = probeProvider;
    }

    @GetMapping("/runtime")
    public R runtime() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("application", env.getProperty("spring.application.name", "erp-system"));
        String[] active = env.getActiveProfiles();
        m.put("profiles", active.length == 0 ? "default（零配置模式）" : String.join(",", active));
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("session", tokenStore.stats());
        m.put("cache", cacheHub.stats());
        CloudProbeService probe = probeProvider.getIfAvailable();
        m.put("cloudEnabled", probe != null);
        if (probe != null) {
            m.put("cloud", probe.status());
        }
        return R.ok(m);
    }

    @GetMapping("/cloud-probe")
    public R cloudProbe() {
        CloudProbeService probe = probeProvider.getIfAvailable();
        if (probe == null) {
            throw new BizException("Spring Cloud 模式未启用，请用 start-cloud.bat 启动");
        }
        return R.ok(probe.probe());
    }
}
