//DEPS com.google.code.gson:gson:2.10.1

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class WeatherFetcher {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Error: City name argument is required.");
            System.exit(1);
        }

        String city = args[0];

        try {
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.toString());
            // wttr.in natively supports a j1 format which returns standard JSON
            String url = "https://wttr.in/" + encodedCity + "?format=j1";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("Fatal Error: Failed to fetch weather. HTTP Status: " + response.statusCode());
                System.err.println("Response Body: " + response.body());
                System.exit(1);
            }

            JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
            
            JsonArray currentConditionArray = jsonResponse.getAsJsonArray("current_condition");
            if (currentConditionArray == null || currentConditionArray.isEmpty()) {
                System.err.println("Fatal Error: Unexpected API response format. Missing 'current_condition'.");
                System.exit(1);
            }

            JsonObject currentCondition = currentConditionArray.get(0).getAsJsonObject();

            String tempC = currentCondition.get("temp_C").getAsString();
            String windSpeed = currentCondition.get("windspeedKmph").getAsString();
            
            JsonArray weatherDescArray = currentCondition.getAsJsonArray("weatherDesc");
            String weatherDesc = "Unknown";
            if (weatherDescArray != null && !weatherDescArray.isEmpty()) {
                weatherDesc = weatherDescArray.get(0).getAsJsonObject().get("value").getAsString();
            }

            System.out.println("Weather for " + city + ":");
            System.out.println("Temperature: " + tempC + " °C");
            System.out.println("Description: " + weatherDesc);
            System.out.println("Wind Speed:  " + windSpeed + " km/h");

        } catch (Exception e) {
            System.err.println("Fatal error occurred while fetching the weather:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}