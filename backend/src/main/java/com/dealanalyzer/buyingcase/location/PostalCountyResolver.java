package com.dealanalyzer.buyingcase.location;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Census ZIP areas that touch Florida counties; never choose the largest land share. */
@Service
public class PostalCountyResolver {
  private final JdbcTemplate database;

  public PostalCountyResolver(JdbcTemplate database) {
    this.database = database;
  }

  public List<String> floridaCounties(String postalCode) {
    return database.queryForList(
        "select county_name from postal_counties where postal_code = ? order by county_name",
        String.class, postalCode);
  }
}
