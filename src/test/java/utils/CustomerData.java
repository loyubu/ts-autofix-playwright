package utils;

import net.datafaker.Faker;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A made-up customer and car for one test, generated with Faker's Nigerian locale.
 *
 * Every email address is pw.{first}.{last}.{number}@transsahara.com: the "pw." prefix marks the
 * row as a Playwright test in Airtable, and the domain's catch-all inbox means no real person is
 * ever emailed.
 */
public class CustomerData {

    private static final Faker FAKER = new Faker(new Locale("en", "NG"));

    private static final List<String[]> CARS = List.of(
            new String[]{"Toyota", "Corolla 2014"},
            new String[]{"Toyota", "Camry 2012"},
            new String[]{"Toyota", "Highlander 2016"},
            new String[]{"Toyota", "RAV4 2018"},
            new String[]{"Honda", "Accord 2015"},
            new String[]{"Honda", "CR-V 2013"},
            new String[]{"Lexus", "RX 350 2017"},
            new String[]{"Lexus", "ES 350 2019"},
            new String[]{"Hyundai", "Elantra 2016"},
            new String[]{"Kia", "Rio 2015"},
            new String[]{"Kia", "Sportage 2019"},
            new String[]{"Nissan", "Almera 2014"},
            new String[]{"Mercedes-Benz", "C300 2015"},
            new String[]{"Peugeot", "406 2008"},
            new String[]{"Ford", "Explorer 2017"});

    private final String firstName;
    private final String lastName;
    private final String phone;
    private final String email;
    private final String branch;
    private final String carMake;
    private final String carModel;
    private final int amountNaira;

    public CustomerData() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        firstName = FAKER.name().firstName();
        lastName = FAKER.name().lastName();
        phone = FAKER.phoneNumber().cellPhone();
        email = ("pw." + slug(firstName) + "." + slug(lastName) + "." + random.nextInt(1000, 10000)
                + "@transsahara.com");
        branch = Constants.BRANCHES[random.nextInt(Constants.BRANCHES.length)];
        String[] car = CARS.get(random.nextInt(CARS.size()));
        carMake = car[0];
        carModel = car[1];
        amountNaira = random.nextInt(15, 251) * 1000;
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getBranch() {
        return branch;
    }

    public String getCarMake() {
        return carMake;
    }

    public String getCarModel() {
        return carModel;
    }

    public int getAmountNaira() {
        return amountNaira;
    }

    @Override
    public String toString() {
        return getFullName() + " <" + email + "> | " + branch + " | " + carMake + " " + carModel;
    }
}
