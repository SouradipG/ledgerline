package dev.souradip.ledgerline.locking;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DebitStrategies {

    private final Map<LockingMode, DebitStrategy> byMode = new EnumMap<>(LockingMode.class);

    DebitStrategies(List<DebitStrategy> strategies) {
        strategies.forEach(s -> byMode.put(s.mode(), s));
    }

    public DebitStrategy get(LockingMode mode) {
        return byMode.get(mode);
    }
}
