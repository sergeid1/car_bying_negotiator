package com.dealanalyzer.vehicle.safety;

import static org.assertj.core.api.Assertions.*;
import static com.dealanalyzer.vehicle.safety.NhtsaVehicleSafetyProviderTest.live;
import com.dealanalyzer.journey.vin.NhtsaVpicVinDecoder;
import com.dealanalyzer.journey.vin.VehicleAttribute;
import com.dealanalyzer.vehicle.Vin;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * VIN to vPIC decode to NHTSA recalls and ratings, against one local stub serving trimmed live answers
 * captured on 2026-10-22 (fixtures/nhtsa-safety). The VINs are public auction records; nothing else
 * from those listings is kept.
 */
class VinDecodeToVehicleSafetyTest {
  HttpServer server;
  final List<String> paths = new CopyOnWriteArrayList<>();
  @AfterEach void stop() { if (server != null) server.stop(0); }

  String start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().toString(); paths.add(path);
      int status = 200; String body;
      if (path.startsWith("/vpic/DecodeVinValues/2HGFC2F87KH550788")) body = live("vpic-2019-honda-civic.json");
      else if (path.startsWith("/vpic/DecodeVinValues/3GCUKREC0JG358411")) body = live("vpic-2018-chevrolet-silverado.json");
      else if (path.equals("/recalls/recallsByVehicle?make=HONDA&model=Civic&modelYear=2019")) body = live("recalls-2019-honda-civic.json");
      else if (path.startsWith("/SafetyRatings/modelyear/2019/make/HONDA/model/Civic")) body = live("ratings-variants-2019-honda-civic.json");
      else if (path.startsWith("/SafetyRatings/VehicleId/14009")) body = live("rating-14009-2019-honda-civic-4dr.json");
      else if (path.startsWith("/SafetyRatings/VehicleId/14005")) body = live("rating-14005-2019-honda-civic-2dr.json");
      // NHTSA lists the truck as "Silverado 1500"; vPIC's "Silverado" gets live NHTSA's no-records answers.
      else if (path.startsWith("/recalls/")) { status = 400; body = live("recalls-no-records-http-400.json"); }
      else body = "{\"Count\":0,\"Message\":\"Results returned successfully\",\"Results\":[]}";
      var bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  VehicleSafetyProvider.Facts lookUp(String url, String vin) {
    var decoded = new NhtsaVpicVinDecoder(url + "/vpic", Duration.ofSeconds(2), 1, Duration.ZERO, new ObjectMapper())
        .decode(Vin.validated(vin)).attributes();
    var car = new VehicleSafetyProvider.Vehicle(Integer.parseInt(decoded.get(VehicleAttribute.MODEL_YEAR)),
        decoded.get(VehicleAttribute.MAKE), decoded.get(VehicleAttribute.MODEL));
    return new NhtsaVehicleSafetyProvider(url, Duration.ofSeconds(2), 1, Duration.ZERO, new ObjectMapper()).retrieve(car);
  }

  @Test void aDecodedCivicFindsItsRecallsAndEveryRatedVariant() throws Exception {
    var facts = lookUp(start(), "2HGFC2F87KH550788");
    assertThat(facts.vehicle()).isEqualTo(new VehicleSafetyProvider.Vehicle(2019, "HONDA", "Civic"));
    assertThat(facts.recalls()).extracting(VehicleSafetyProvider.Recall::campaignNumber).containsExactly("20V314000", "26V332000");
    assertThat(facts.ratings()).extracting(VehicleSafetyProvider.Rating::vehicleId).containsExactly("14009", "14005");
    assertThat(paths).noneMatch(path -> !path.startsWith("/vpic") && path.contains("2HGFC2F87KH550788"));
  }

  /** The open item in ticket 116: vPIC's model name differs from NHTSA's, so nothing is found and nothing is guessed. */
  @Test void aDecodedSilveradoIsAnHonestEmptyAnswerBecauseNhtsaSpellsItSilverado1500() throws Exception {
    var facts = lookUp(start(), "3GCUKREC0JG358411");
    assertThat(facts.vehicle()).isEqualTo(new VehicleSafetyProvider.Vehicle(2018, "CHEVROLET", "Silverado"));
    assertThat(facts.status()).isEqualTo("AVAILABLE");
    assertThat(facts.recalls()).isEmpty(); assertThat(facts.ratings()).isEmpty();
    assertThat(paths).contains("/recalls/recallsByVehicle?make=CHEVROLET&model=Silverado&modelYear=2018",
        "/SafetyRatings/modelyear/2018/make/CHEVROLET/model/Silverado?format=json");
  }
}
