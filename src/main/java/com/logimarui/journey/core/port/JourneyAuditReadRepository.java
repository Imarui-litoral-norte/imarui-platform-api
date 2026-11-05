package com.logimarui.journey.core.port;
import com.logimarui.journey.core.model.JourneyAudit;
import java.util.List;
import java.util.Map;
public interface JourneyAuditReadRepository {
    Map<JourneyAudit.Key, JourneyAudit.Deliveries> deliveries(List<JourneyAudit.SelectedMap> maps);
}
