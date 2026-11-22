package patches.maps.gearslip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The car card draws its own arrow: a glyph sits on the baseline and reads low. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BydBadgeTextTest {

    @Test
    public void theArrowIsADrawnSpanOnTheDigitsOpticalCentre() {
        TextView target = new TextView(RuntimeEnvironment.getApplication());
        target.setTextSize(14f);
        target.setTextColor(0xFF137333);

        CharSequence badge = BydVehicle.badgeText(target, 32, 27);

        assertEquals("32%\u2009\u2192\u200927%", badge.toString());
        android.text.style.ReplacementSpan[] arrows = ((Spanned) badge).getSpans(
                0, badge.length(), android.text.style.ReplacementSpan.class);
        assertEquals(1, arrows.length);
        assertTrue(arrows[0] instanceof BydVehicle.CenteredArrowSpan);
        assertTrue(arrows[0].getSize(new android.graphics.Paint(), badge, 0, 1, null) > 0);
    }

    @Test
    public void noArrowWhenThereIsNothingToCompare() {
        TextView target = new TextView(RuntimeEnvironment.getApplication());
        target.setTextSize(14f);

        assertEquals("27%", BydVehicle.badgeText(target, 27, 27).toString());
        assertEquals("27%", BydVehicle.badgeText(target, -1, 27).toString());
    }
}
