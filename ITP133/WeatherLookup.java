import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Scanner;

public class WeatherLookup {

    static class WeatherException extends Exception {
        WeatherException(String message) { super(message); }
    }

    record Place(String name, String country, double lat, double lon) { }
    record DayWeather(LocalDate date, double tempMax, double tempMin,
                      double windMax, int code) { }

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    static String describe(int code) {
        switch (code) {
            case 0: return "Clear sky";
            case 1: return "Mainly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 61: case 63: case 65: return "Rain";
            case 71: case 73: case 75: return "Snow";
            case 80: case 81: case 82: return "Rain showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm with hail";
            default: return "Weather code " + code;
        }
    }

    static String get(String url) throws WeatherException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String reason = "";
                try {
                    reason = JsonParser.parseString(response.body())
                            .getAsJsonObject().get("reason").getAsString();
                } catch (Exception e) {
                    // no reason text given
                }
                throw new WeatherException("The service returned an error (status "
                        + response.statusCode() + (reason.isEmpty() ? "" : ": " + reason) + ").");
            }
            return response.body();

        } catch (HttpTimeoutException e) {
            throw new WeatherException("The service took too long to respond. Please try again.");
        } catch (IOException e) {
            throw new WeatherException("Cannot reach the weather service. Please check your internet connection.");
        } catch (InterruptedException e) {
            throw new WeatherException("The request was interrupted. Please try again.");
        }
    }

    static Place findPlace(String input) throws WeatherException {
        String name = input;
        String country = "";
        if (input.contains(",")) {
            name = input.substring(0, input.indexOf(",")).trim();
            country = input.substring(input.indexOf(",") + 1).trim();
        }

        String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&count=10&language=en&format=json";
        JsonObject root = JsonParser.parseString(get(url)).getAsJsonObject();

        if (!root.has("results")) {
            throw new WeatherException("No place found for '" + name
                    + "'. Check the spelling or try a nearby city.");
        }
        JsonArray results = root.getAsJsonArray("results");

        for (JsonElement e : results) {
            JsonObject r = e.getAsJsonObject();
            String c = r.has("country") ? r.get("country").getAsString() : "";
            if (country.isEmpty() || c.equalsIgnoreCase(country)) {
                return new Place(r.get("name").getAsString(), c,
                        r.get("latitude").getAsDouble(),
                        r.get("longitude").getAsDouble());
            }
        }
        throw new WeatherException("Places named '" + name + "' exist, but none in '"
                + country + "'.");
    }

    static DayWeather getDayWeather(Place p, LocalDate date) throws WeatherException {
        long days = ChronoUnit.DAYS.between(LocalDate.now(), date);
        String base = (days < -7)
                ? "https://archive-api.open-meteo.com/v1/archive"
                : "https://api.open-meteo.com/v1/forecast";

        String url = base + "?latitude=" + p.lat() + "&longitude=" + p.lon()
                + "&start_date=" + date + "&end_date=" + date
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,wind_speed_10m_max"
                + "&timezone=auto";
        JsonObject root = JsonParser.parseString(get(url)).getAsJsonObject();

        if (!root.has("daily")) {
            throw new WeatherException("No weather data is available for that date yet. "
                    + "Recent days may be incomplete.");
        }
        JsonObject daily = root.getAsJsonObject("daily");

        try {
            double tempMax = daily.getAsJsonArray("temperature_2m_max").get(0).getAsDouble();
            double tempMin = daily.getAsJsonArray("temperature_2m_min").get(0).getAsDouble();
            double windMax = daily.getAsJsonArray("wind_speed_10m_max").get(0).getAsDouble();
            int code = daily.getAsJsonArray("weather_code").get(0).getAsInt();
            return new DayWeather(date, tempMax, tempMin, windMax, code);
        } catch (RuntimeException e) {
            // missing list, empty list, or null entry
            throw new WeatherException("No weather data is available for that date yet. "
                    + "Recent days may be incomplete.");
        }
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("Enter city or country (or quit): ");
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("quit")) {
                System.out.println("Goodbye!");
                break;
            }

            System.out.print("Enter date (YYYY-MM-DD): ");
            String dateText = scanner.nextLine().trim();

            try {
                if (input.isEmpty()) {
                    throw new WeatherException("Please enter a place name.");
                }

                LocalDate date = LocalDate.parse(dateText);

                long daysAhead = ChronoUnit.DAYS.between(LocalDate.now(), date);
                if (daysAhead > 16) {
                    throw new WeatherException("Forecasts reach only 16 days ahead (up to "
                            + LocalDate.now().plusDays(16) + ").");
                }

                Place place = findPlace(input);
                DayWeather w = getDayWeather(place, date);

                System.out.println("=== Weather Report ===");
                System.out.printf("Place       : %s, %s (%.2f, %.2f)%n",
                        place.name(), place.country(), place.lat(), place.lon());
                System.out.println("Date        : " + w.date());
                System.out.printf("Temperature : %.1f to %.1f deg C%n", w.tempMin(), w.tempMax());
                System.out.printf("Wind        : up to %.1f km/h%n", w.windMax());
                System.out.println("Conditions  : " + describe(w.code()));

            } catch (DateTimeParseException e) {
                System.out.println("Invalid date. Use YYYY-MM-DD, for example 2026-11-10.");
            } catch (WeatherException e) {
                System.out.println(e.getMessage());
            } catch (JsonSyntaxException e) {
                System.out.println("The service sent an answer that could not be read.");
            }
            System.out.println();
        }
    }
}