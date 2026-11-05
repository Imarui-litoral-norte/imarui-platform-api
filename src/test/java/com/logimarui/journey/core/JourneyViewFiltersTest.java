package com.logimarui.journey.core;
import com.logimarui.operationalread.core.model.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class JourneyViewFiltersTest {
 private final OperationalContext context = new OperationalContext(LocalDate.of(2026,10,1),1L,"LIVE",null,42L,"motorista","Driver",7L,"ABC1234",null,null,null,null);
 private JourneyViewFilters filters(String origin,List<String> vehicles,List<String> excluded,List<String> included,String reason) {
  return new JourneyViewFilters(origin,vehicles,excluded,included,"",reason,"");
 }
 @Test void combinesOriginVehicleAndEmployeeSelections() {
  assertTrue(filters("LIVE",List.of(),List.of(),List.of("42"),"").matches(context,null));
  assertFalse(filters("HISTORICO",List.of(),List.of(),List.of(),"").matches(context,null));
  assertFalse(filters("all",List.of("7"),List.of(),List.of(),"").matches(context,null));
  assertFalse(filters("all",List.of(),List.of("42"),List.of("42"),"").matches(context,null));
  assertFalse(filters("all",List.of(),List.of(),List.of("99"),"").matches(context,null));
 }
 @Test void handlesEmptyEmployeesAndExpurgeDetails() {
  var blank = new OperationalContext(context.date(),1L,"HISTORICO",null,null,"motorista",null,7L,null,null,null,null,null);
  assertFalse(filters("all",List.of(),List.of("__blank__"),List.of(),"").matches(blank,null));
  assertTrue(filters("all",List.of(),List.of(),List.of("__blank__"),"").matches(blank,null));
  var expurge = new Expurge(true,1L,"1","Manual","Manutencao","Teste",null);
  assertTrue(filters("all",List.of(),List.of(),List.of(),"MANUT").matches(context,expurge));
  assertFalse(filters("all",List.of(),List.of(),List.of(),"MANUT").matches(context,null));
 }
}
