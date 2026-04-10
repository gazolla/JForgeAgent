//DEPS com.google.code.gson:gson:2.10.1

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

public class CryptoPriceFetcher {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Error: Missing cryptocurrency symbol argument.");
            System.err.println("Usage: CryptoPriceFetcher <symbol>");
            System.err.println("Example: CryptoPriceFetcher BTCUSDT");
            System.exit(1);
        }

        String symbol = args[0].trim().toUpperCase();
        String endpoint = "https://api.binance.com/api/v3/ticker/price?symbol=" + symbol;

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 400) {
                System.err.println("Error: Invalid symbol '" + symbol + "'. Please provide a valid cryptocurrency trading pair (e.g., BTCUSDT).");
                System.exit(1);
            } else if (response.statusCode() != 200) {
                System.err.println("Error: API request failed with HTTP status " + response.statusCode());
                System.err.println("Response body: " + response.body());
                System.exit(1);
            }

            String responseBody = response.body();
            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();

            if (jsonObject.has("price")) {
                String price = jsonObject.get("price").getAsString();
                System.out.println("The current price of " + symbol + " is: " + price);
            } else {
                System.err.println("Error: Unexpected JSON structure, missing 'price' field.");
                System.err.println("Response body: " + responseBody);
                System.exit(1);
            }

        } catch (JsonSyntaxException e) {
            System.err.println("Fatal error: Failed to parse JSON response.");
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Fatal error: Network or execution failure while fetching price.");
            e.printStackTrace();
            System.exit(1);
        }
    }
}