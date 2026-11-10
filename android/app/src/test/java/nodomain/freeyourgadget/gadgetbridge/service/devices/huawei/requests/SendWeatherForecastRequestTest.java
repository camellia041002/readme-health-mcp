package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiTLV;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.Weather;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiSupportProvider;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SendWeatherForecastRequestTest extends TestBase {
    private SendWeatherForecastRequest requestWithStatus(Integer status) {
        HuaweiSupportProvider support = mock(HuaweiSupportProvider.class);
        SendWeatherForecastRequest request = new SendWeatherForecastRequest(
                support, new Weather.Settings(), new WeatherSpec());
        HuaweiPacket packet = mock(HuaweiPacket.class);
        HuaweiTLV tlv = new HuaweiTLV();
        if (status != null) tlv.put(0x7f, status.intValue());
        when(packet.getTlv()).thenReturn(tlv);
        request.receivedPacket = packet;
        return request;
    }

    @Test
    public void acceptsSuccess() throws Exception {
        requestWithStatus(0x000186A0).processResponse();
    }

    @Test
    public void reportsObservedBand10Rejection() {
        Request.ResponseParseException error = assertThrows(Request.ResponseParseException.class,
                () -> requestWithStatus(0x0001C139).processResponse());
        assertTrue(error.getMessage().contains("0x1c139"));
    }

    @Test
    public void missingStatusIsNotSuccess() {
        assertThrows(Request.ResponseParseException.class,
                () -> requestWithStatus(null).processResponse());
    }
}
