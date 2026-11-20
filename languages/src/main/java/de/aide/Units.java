package de.aide;

import systems.uom.ucum.format.UCUMFormat;
import systems.uom.ucum.internal.format.TokenMgrError;

import javax.measure.Unit;
import javax.measure.UnitConverter;
import javax.measure.format.UnitFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Unit handling through the UCUM unit library. Units are compared by physical
 * dimension, not by name.
 */
public final class Units {

  private static final UnitFormat UCUM = UCUMFormat.getInstance(UCUMFormat.Variant.CASE_SENSITIVE);

  /** Common spellings that are not UCUM codes. */
  private static final Map<String, String> ALIASES = Map.of(
      "degC", "Cel",
      "°C", "Cel",
      "degF", "[degF]",
      "°F", "[degF]",
      "rpm", "/min",
      "µm", "um",
      "µs", "us");

  /** UNECE common codes of EUInformation.UnitId and their UCUM codes. */
  private static final Map<String, String> UNECE = Map.ofEntries(
      Map.entry("CEL", "Cel"), Map.entry("KEL", "K"), Map.entry("FAH", "[degF]"),
      Map.entry("MTR", "m"), Map.entry("CMT", "cm"), Map.entry("MMT", "mm"), Map.entry("KMT", "km"),
      Map.entry("4H", "um"), Map.entry("SEC", "s"), Map.entry("C26", "ms"), Map.entry("B98", "us"),
      Map.entry("MIN", "min"), Map.entry("HUR", "h"), Map.entry("KGM", "kg"), Map.entry("GRM", "g"),
      Map.entry("TNE", "t"), Map.entry("NEW", "N"), Map.entry("B47", "kN"), Map.entry("NU", "N.m"),
      Map.entry("PAL", "Pa"), Map.entry("KPA", "kPa"), Map.entry("BAR", "bar"), Map.entry("MBR", "mbar"),
      Map.entry("VLT", "V"), Map.entry("KVT", "kV"), Map.entry("AMP", "A"), Map.entry("4K", "mA"),
      Map.entry("WTT", "W"), Map.entry("KWT", "kW"), Map.entry("MAW", "MW"), Map.entry("KWH", "kW.h"),
      Map.entry("JOU", "J"), Map.entry("KJO", "kJ"), Map.entry("OHM", "Ohm"), Map.entry("HTZ", "Hz"),
      Map.entry("KHZ", "kHz"), Map.entry("RPM", "/min"), Map.entry("RPS", "/s"), Map.entry("MTS", "m/s"),
      Map.entry("KMH", "km/h"), Map.entry("C16", "mm/s"), Map.entry("2X", "m/min"), Map.entry("MSK", "m/s2"),
      Map.entry("P1", "%"), Map.entry("LTR", "l"), Map.entry("MLT", "ml"), Map.entry("MTQ", "m3"),
      Map.entry("L2", "l/min"), Map.entry("MQH", "m3/h"), Map.entry("DD", "deg"), Map.entry("C62", "1"));

  private Units() {
  }

  /**
   * The UCUM code of an EUInformation.UnitId, which encodes the UNECE common code in its bytes,
   * or empty if the code is unknown.
   */
  public static Optional<String> fromUnitId(int unitId) {
    return Optional.ofNullable(UNECE.get(uneceCode(unitId)));
  }

  /** The UNECE common code that an EUInformation.UnitId encodes, for example CEL. */
  public static String uneceCode(int unitId) {
    StringBuilder code = new StringBuilder();
    for (int shift = 24; shift >= 0; shift -= 8) {
      int c = (unitId >> shift) & 0xFF;
      if (c != 0) {
        code.append((char) c);
      }
    }
    return code.toString();
  }

  /** Strips the brackets of a bracketed unit literal. */
  public static String symbol(String text) {
    String value = text.trim();
    if (value.startsWith("[") && value.endsWith("]") && !value.equals("[degF]")) {
      value = value.substring(1, value.length() - 1).trim();
    }
    return value;
  }

  public static Optional<Unit<?>> parse(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    String value = symbol(text);
    value = ALIASES.getOrDefault(value, value);
    try {
      return Optional.of(UCUM.parse(value));
    } catch (RuntimeException | TokenMgrError e) { // The UCUM lexer throws an Error for unknown characters.
      return Optional.empty();
    }
  }

  public static boolean sameDimension(Unit<?> a, Unit<?> b) {
    return a.isCompatible(b);
  }

  public static boolean sameUnit(Unit<?> a, Unit<?> b) {
    if (!a.isCompatible(b)) {
      return false;
    }
    double[] linear = linear(a, b);
    return Math.abs(linear[0] - 1) < 1e-12 && Math.abs(linear[1]) < 1e-12;
  }

  /** Returns {scale, offset} so that target = source * scale + offset. */
  public static double[] linear(Unit<?> source, Unit<?> target) {
    try {
      UnitConverter converter = source.getConverterToAny(target);
      double offset = converter.convert(0.0);
      double scale = converter.convert(1.0) - offset;
      return new double[] {scale, offset};
    } catch (javax.measure.IncommensurableException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
