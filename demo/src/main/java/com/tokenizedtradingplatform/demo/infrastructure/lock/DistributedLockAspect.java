package com.tokenizedtradingplatform.demo.infrastructure.lock;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Around-advice for {@link DistributedLock}. Order(1) — runs OUTSIDE
 * {@code @Transactional} so the lock is held for the full transaction
 * including commit, and released only after commit.
 *
 * Without this ordering, the lock could be released before the DB commits,
 * letting another thread observe pre-commit state.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
public class DistributedLockAspect {

    private final DistributedLockService lockService;

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParserContext templateCtx = new TemplateParserContext(); // "#{ ... }"
    private final ParameterNameDiscoverer paramDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint pjp, DistributedLock distributedLock) throws Throwable {
        String key = resolveKey(pjp, distributedLock.key());
        RLock lock = lockService.acquire(key, distributedLock.waitSeconds(), distributedLock.leaseSeconds());
        try {
            return pjp.proceed();
        } finally {
            lockService.release(lock);
        }
    }

    private String resolveKey(ProceedingJoinPoint pjp, String template) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        String[] paramNames = paramDiscoverer.getParameterNames(method);
        Object[] args = pjp.getArgs();

        StandardEvaluationContext ctx = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                ctx.setVariable(paramNames[i], args[i]);
            }
        }

        Expression expression = parser.parseExpression(template, templateCtx);
        String resolved = expression.getValue(ctx, String.class);
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException("Lock key resolved to empty: template=" + template);
        }
        return resolved;
    }
}
