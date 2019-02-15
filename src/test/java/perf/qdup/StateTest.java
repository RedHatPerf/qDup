package perf.qdup;

import org.junit.Test;
import perf.yaup.json.Json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StateTest {




    @Test
    public void get_nested_search(){
        State state = new State("");
        Json json = new Json();
        json.set("bar",new Json());
        json.getJson("bar").set("biz","value");
        state.set("foo", json);
        Object found;
        found = state.get("$.foo");
        assertTrue("state should have $.foo",found!=null);
        assertTrue("state.get($.foo) should be Json",found instanceof Json);
        found = state.get("$.foo.bar");
        assertTrue("state should have $.foo.bar",found!=null);
        assertTrue("state.get($.foo.bar) should be Json",found instanceof Json);
    }

    @Test
    public void emptyPrefixMakesParentsReadOnly(){
        State top = new State("TOP.");
        State mid = top.addChild("mid","MID.");
        State bot = mid.addChild("bot","");

        bot.set("TOP.foo","foo");

        assertFalse("top should be immutable from bot",top.has("foo"));
        assertTrue(bot.has("TOP.foo"));
        mid.set("TOP.foo","foo");
        assertEquals("top[foo] should be settable from mid","foo",top.get("foo"));
    }

    @Test
    public void parentPrefix(){
        State top = new State("TOP.");
        State mid = top.addChild("mid","MID.");
        State bot = mid.addChild("bot",null);

        top.set("foo","top");
        mid.set("foo","mid");

        assertEquals("bot[foo] should get the value from mid","mid",bot.get("foo"));
        assertEquals("bot[MID.foo] should get the value from mid","mid",bot.get("MID.foo"));
        assertEquals("bot[TOP.foo] should get the value from top","top",bot.get("TOP.foo"));

        bot.set("foo","bot");
        assertEquals("bot[foo] should get the value from bot","bot",bot.get("foo"));

        bot.set("MID.foo","middle");
        assertEquals("mid[foo] should now be middle","middle",mid.get("foo"));
        bot.set("TOP.foo","topper");
        assertEquals("top[foo] should now be topper","topper",top.get("foo"));
    }
}
