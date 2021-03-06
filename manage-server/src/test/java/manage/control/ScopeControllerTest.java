package manage.control;

import manage.AbstractIntegrationTest;
import manage.model.Scope;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_CONFLICT;
import static org.apache.http.HttpStatus.SC_OK;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ScopeControllerTest extends AbstractIntegrationTest {

    @Before
    public void before() throws Exception {
        super.before();
        MongoTemplate mongoTemplate = mongoTemplate();
        List<Scope> scopes = Arrays.asList(
                new Scope("1", 0L, "groups", descriptions()),
                new Scope("2", 0L, "openid", descriptions()),
                new Scope("3", 0L, "not-used", descriptions())
        );
        mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Scope.class)
                .remove(Query.query(Criteria.where("_id").exists(true)))
                .insert(scopes)
                .execute();
        await().until(() -> this.countScopes() == scopes.size());
    }

    @Test
    public void save() {
        String id = given()
                .when()
                .body(new Scope("new-scope", descriptions()))
                .header("Content-type", "application/json")
                .post("manage/api/client/scopes")
                .then()
                .statusCode(SC_OK)
                .extract().path("id");
        assertNotNull(id);
    }

    @Test
    public void saveDuplicateKey() {
        given()
                .when()
                .body(new Scope("groups", descriptions()))
                .header("Content-type", "application/json")
                .post("manage/api/client/scopes")
                .then()
                .statusCode(SC_BAD_REQUEST)
                .body("exception", is("manage.exception.ScopeDuplicateNameException"));
    }

    @Test
    public void all() {
        List<Scope> scopes = given()
                .when()
                .header("Content-type", "application/json")
                .get("manage/api/client/scopes")
                .then()
                .extract().body().jsonPath().getList(".", Scope.class);
        assertEquals(3, scopes.size());
    }

    @Test
    public void fetchValues() {
        List<String> scopes = given()
                .when()
                .header("Content-type", "application/json")
                .get("manage/api/client/fetch/scopes")
                .then()
                .extract().body().jsonPath().getList(".", String.class);
        assertEquals(3, scopes.size());
    }

    @Test
    public void update() {
        Scope scope = given()
                .when()
                .header("Content-type", "application/json")
                .get("manage/api/client/scopes/3")
                .as(Scope.class);

        ReflectionTestUtils.setField(scope, "name", "changed");

        given()
                .when()
                .body(scope)
                .header("Content-type", "application/json")
                .put("manage/api/client/scopes")
                .then()
                .statusCode(SC_OK);

        scope = given()
                .when()
                .header("Content-type", "application/json")
                .get("manage/api/client/scopes/3")
                .as(Scope.class);
        assertEquals("changed", scope.getName());
        assertEquals(new Long(1), scope.getVersion());
    }

    @Test
    public void updateDescriptions() {
        Scope scope = given()
                .when()
                .header("Content-type", "application/json")
                .get("manage/api/client/scopes/3")
                .as(Scope.class);

        scope.getDescriptions().put("pt", "pt-language");
        scope = given()
                .when()
                .body(scope)
                .header("Content-type", "application/json")
                .put("manage/api/client/scopes")
                .as(Scope.class);
        assertEquals("pt-language", scope.getDescriptions().get("pt"));
    }

    @Test
    public void delete() {
        long pre = countScopes();
        String id = given()
                .when()
                .body(new Scope("nope", descriptions()))
                .header("Content-type", "application/json")
                .post("manage/api/client/scopes")
                .then()
                .statusCode(SC_OK)
                .extract().path("id");
        assertNotNull(id);

        long after = countScopes();
        assertEquals(pre + 1, after);

        given()
                .when()
                .delete("manage/api/client/scopes/" + id)
                .then()
                .statusCode(SC_OK);

        long afterDelete = countScopes();
        assertEquals(pre, afterDelete);
    }

    @Test
    public void deleteWithConflicts() {
        String id = mongoTemplate().findOne(Query.query(Criteria.where("name").is("openid")), Scope.class).getId();

        Map map = given()
                .when()
                .delete("manage/api/client/scopes/" + id)
                .then()
                .statusCode(SC_CONFLICT)
                .extract()
                .as(Map.class);
        assertEquals("[{\"entityid\":\"https@//oidc.rp\",\"id\":\"9\"},{\"entityid\":\"https@//oidc.rp.resourceServer\",\"id\":\"10\"}]", map.get("message"));
    }

    @Test
    public void languages() {
        List<String> languages = given()
                .when()
                .header("Content-type", "application/json")
                .get("manage/api/client/scopes_languages")
                .then()
                .extract().body().jsonPath().getList(".", String.class);
        assertEquals(2, languages.size());
    }

    private Map<String, String> descriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("en", "English description");
        descriptions.put("nl", "Nederlandse omschrijving");
        return descriptions;
    }

    private long countScopes() {
        return mongoTemplate().count(new Query(), Scope.class);
    }
}
