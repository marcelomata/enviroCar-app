package org.envirocar.algorithm;

import android.location.Location;

import com.squareup.otto.Subscribe;

import org.envirocar.core.entity.Measurement;
import org.envirocar.core.entity.MeasurementImpl;
import org.envirocar.core.events.gps.GpsDOP;
import org.envirocar.core.events.gps.GpsDOPEvent;
import org.envirocar.core.events.gps.GpsLocationChangedEvent;
import org.envirocar.core.logging.Logger;
import org.envirocar.obd.events.PropertyKeyEvent;
import org.envirocar.obd.events.Timestamped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rx.Observable;
import rx.Subscriber;

/**
 * TODO JavaDoc
 */
public class InterpolationMeasurementProvider extends AbstractMeasurementProvider {
    private static final Logger LOG = Logger.getLogger(InterpolationMeasurementProvider.class);

    private Map<Measurement.PropertyKey, List<PropertyKeyEvent>> bufferedResponses = new
            HashMap<>();
    private long firstTimestampToBeConsidered;
    private long lastTimestampToBeConsidered;

    @Override
    public Observable.Operator<Measurement, PropertyKeyEvent> getOBDValueConsumer() {
        return subscriber -> new Subscriber<PropertyKeyEvent>() {
            @Override
            public void onCompleted() {
                subscriber.onCompleted();
            }

            @Override
            public void onError(Throwable e) {
                subscriber.onError(e);
            }

            @Override
            public void onNext(PropertyKeyEvent pke) {
                consider(pke);
            }
        };
    }

    /*
         * TODO implement listing for GPS DOP Events
         */
    @Override
    public Observable<Measurement> measurements(long samplingRate) {
        return Observable.create(new Observable.OnSubscribe<Measurement>() {
            @Override
            public void call(Subscriber<? super Measurement> subscriber) {
                LOG.info("measurements(): start collecting data");
                subscriber.onStart();

                while (!subscriber.isUnsubscribed()) {
                    synchronized (InterpolationMeasurementProvider.this) {
                        /**
                         * wait the sampling rate
                         */
                        try {
                            InterpolationMeasurementProvider.this.wait(samplingRate);
                        } catch (InterruptedException e) {
                            subscriber.onError(e);
                        }

                        Measurement m = createMeasurement();

                        if (m != null && m.getLatitude() != null && m.getLongitude() != null
                                && m.hasProperty(Measurement.PropertyKey.SPEED)) {
                            subscriber.onNext(m);
                        }
                    }
                }
                LOG.info("measurements(): finished the collection of data.");
                subscriber.onCompleted();
            }
        });
    }

    private synchronized Measurement createMeasurement() {
        /**
         * use the middle of the time window
         */
        long targetTimestamp = firstTimestampToBeConsidered + ((lastTimestampToBeConsidered -
                firstTimestampToBeConsidered) / 2);

        Measurement m = new MeasurementImpl();
        m.setTime(targetTimestamp);

        for (Measurement.PropertyKey pk : this.bufferedResponses.keySet()) {
            appendToMeasurement(pk, this.bufferedResponses.get(pk), m);
        }

        /**
         * clear the buffer of DataResponses to be considered
         */
        clearBuffer();
        setPosition(m, getAndClearPositionBuffer());

        return m;
    }

    private void setPosition(Measurement m, List<Position> positionBuffer) {
        if (positionBuffer == null || positionBuffer.isEmpty()) {
            return;
        }

        if (positionBuffer.size() == 1) {
            Position pos = positionBuffer.get(0);
            m.setLatitude(pos.getLatitude());
            m.setLongitude(pos.getLongitude());
        } else {
            long targetTimestamp = m.getTime();

            /**
             * find the closest two measurements
             */
            int startIndex = findStartIndex(positionBuffer, targetTimestamp);
            Position start = positionBuffer.get(startIndex);
            Position end = startIndex + 1 < positionBuffer.size() ? positionBuffer.get(startIndex
                    + 1) : null;

            double lat = interpolateTwo(start.getLatitude(), end != null ? end.getLatitude() :
                            null, targetTimestamp, start.getTimestamp(),
                    end != null ? end.getTimestamp() : 0L);
            double lon = interpolateTwo(start.getLongitude(), end != null ? end.getLongitude() :
                            null, targetTimestamp, start.getTimestamp(),
                    end != null ? end.getTimestamp() : 0L);

            m.setLatitude(lat);
            m.setLongitude(lon);
        }

    }

    private void appendToMeasurement(Measurement.PropertyKey pk, List<PropertyKeyEvent>
            dataResponses, Measurement m) {
        if (pk == null) {
            return;
        }

        switch (pk) {
            case FUEL_SYSTEM_STATUS_CODE:
                m.setProperty(pk, first(dataResponses));
                break;
            default:
                m.setProperty(pk, interpolate(dataResponses, m.getTime()));
                break;
        }

    }

    private Double first(List<PropertyKeyEvent> dataResponses) {
        return dataResponses.isEmpty() ? null : dataResponses.get(0).getValue().doubleValue();
    }

    protected Double interpolate(List<PropertyKeyEvent> dataResponses, long targetTimestamp) {
        if (dataResponses.size() <= 1) {
            return first(dataResponses);
        }

        /**
         * find the closest two measurements
         */
        int startIndex = findStartIndex(dataResponses, targetTimestamp);
        PropertyKeyEvent start = dataResponses.get(startIndex);
        PropertyKeyEvent end = startIndex + 1 < dataResponses.size() ? dataResponses.get
                (startIndex + 1) : null;

        return interpolateTwo(start.getValue(), end != null ? end.getValue() : null,
                targetTimestamp, start.getTimestamp(),
                end != null ? end.getTimestamp() : 0L);
    }

    private int findStartIndex(List<? extends Timestamped> dataResponses, long targetTimestamp) {
        int i = 0;
        while (i + 1 < dataResponses.size()) {
            if (dataResponses.get(i).getTimestamp() <= targetTimestamp
                    && dataResponses.get(i + 1).getTimestamp() >= targetTimestamp) {
                return i;
            }

            i++;
        }

        return 0;
    }

    /**
     * @param start           the start value
     * @param end             the end value
     * @param targetTimestamp the target timestamp used for interpolation
     * @param startTimestamp  the timestamp of the start
     * @param endTimestamp    the timestamp of the lend
     * @return the interpolated value
     */
    protected Double interpolateTwo(Number start, Number end, long targetTimestamp,
                                    long startTimestamp, long endTimestamp) {
        if (start == null && end == null) {
            return null;
        }
        if (start == null) {
            return end.doubleValue();
        } else if (end == null) {
            return start.doubleValue();
        }

        float duration = (float) (endTimestamp - startTimestamp);

        float endWeight = (targetTimestamp - startTimestamp) / duration;
        float startWeight = (endTimestamp - targetTimestamp) / duration;

        return start.doubleValue() * startWeight + end.doubleValue() * endWeight;
    }

    private void clearBuffer() {
        for (List<PropertyKeyEvent> drl : this.bufferedResponses.values()) {
            drl.clear();
        }

        /**
         * reset the first timestamp
         */
        this.firstTimestampToBeConsidered = 0;
    }

    @Override
//    @Subscribe
    public synchronized void consider(PropertyKeyEvent pke) {
        updateTimestamps(pke);

        Measurement.PropertyKey pk = pke.getPropertyKey();

        if (pk == null) {
            return;
        }

        if (bufferedResponses.containsKey(pk)) {
            bufferedResponses.get(pk).add(pke);
        } else {
            List<PropertyKeyEvent> list = new ArrayList<>();
            list.add(pke);
            bufferedResponses.put(pk, list);
        }
    }

    @Override
    public synchronized void newPosition(Position pos) {
        super.newPosition(pos);
        updateTimestamps(pos);
    }

    @Subscribe
    public void newLocation(GpsLocationChangedEvent loc) {
        Location location = loc.mLocation;
        long now = System.currentTimeMillis();

        newPosition(new Position(now,
                location.getLatitude(), location.getLongitude()));

        if (location.hasAccuracy()) {
            consider(new PropertyKeyEvent(Measurement.PropertyKey.GPS_ACCURACY, location
                    .getAccuracy(), now));
        }

        if (location.hasAltitude()) {
            consider(new PropertyKeyEvent(Measurement.PropertyKey.GPS_ALTITUDE, location
                    .getAltitude(), now));
        }

        if (location.hasBearing()) {
            consider(new PropertyKeyEvent(Measurement.PropertyKey.GPS_BEARING, location
                    .getBearing(), now));
        }

        if (location.hasSpeed()) {
            consider(new PropertyKeyEvent(
                    Measurement.PropertyKey.GPS_SPEED, location.getSpeed() * 3.6f, now));
        }
    }

    private void updateTimestamps(Timestamped dr) {
        this.lastTimestampToBeConsidered = Math.max(this.lastTimestampToBeConsidered, dr
                .getTimestamp());

        if (this.firstTimestampToBeConsidered == 0) {
            this.firstTimestampToBeConsidered = dr.getTimestamp();
        } else {
            this.firstTimestampToBeConsidered = Math.min(this.firstTimestampToBeConsidered, dr
                    .getTimestamp());
        }
    }

    @Subscribe
    public void receiveGpsDOP(GpsDOPEvent e) {
        GpsDOP dop = e.mDOP;
        long now = System.currentTimeMillis();

        if (dop.hasHdop()) {
            consider(new PropertyKeyEvent(Measurement.PropertyKey.GPS_HDOP, dop.getHdop(), now));
        }

        if (dop.hasVdop()) {
            consider(new PropertyKeyEvent(Measurement.PropertyKey.GPS_VDOP, dop.getVdop(), now));
        }

        if (dop.hasPdop()) {
            consider(new PropertyKeyEvent(Measurement.PropertyKey.GPS_PDOP, dop.getPdop(), now));
        }
    }

}
