package org.cleargps.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

data class GnssLoggerImportStats(
    val rawRows:Int=0,val fixRows:Int=0,val statusRows:Int=0,val agcRows:Int=0,
    val sensorSamples:Int=0,val importedEvents:Int=0,val skippedRows:Int=0
)
data class GnssLoggerImportResult(val decoded:GnssReplayDecodeResult,val stats:GnssLoggerImportStats)

/** Converts Google's GnssLogger CSV to local replay events without retaining absolute coordinates or wall-clock time. */
object GnssLoggerCsvImporter {
    const val MAX_IMPORT_BYTES=GnssReplayCodec.MAX_BYTES
    const val MAX_IMPORT_LINES=GnssReplayCodec.MAX_LINES
    private const val EARTH_RADIUS=6_371_008.8
    private val supported=setOf("Raw","Fix","Status","Agc","Accel","UncalAccel","Gyro","UncalGyro","Mag","UncalMag","OrientationDeg")
    private data class Row(val type:String,val f:List<String>,val line:Int)
    private data class Pending(val t:Long,val order:Int,val e:GnssReplayEvent)
    private data class Fix(val row:Row,val source:GnssReplayPositionSource,val lat:Double,val lon:Double,
        val acc:Double,val speed:Double?,val speedAcc:Double?,val bearing:Double?,val bearingAcc:Double?)
    private data class Agc(val row:Row,val t:Long,val constellation:Int,val hz:Double?,val db:Double)
    private data class Parsed(val rows:List<Row>,val headers:Map<String,Map<String,Int>>,val skipped:Int)

    private val columns=mapOf(
      "Raw" to mapOf("utcTimeMillis" to 1,"Svid" to 11,"State" to 13,"ReceivedSvTimeNanos" to 14,
        "ReceivedSvTimeUncertaintyNanos" to 15,"Cn0DbHz" to 16,"PseudorangeRateMetersPerSecond" to 17,
        "PseudorangeRateUncertaintyMetersPerSecond" to 18,"CarrierFrequencyHz" to 22,"ConstellationType" to 28,
        "AgcDb" to 29,"BasebandCn0DbHz" to 30,"ChipsetElapsedRealtimeNanos" to 36),
      "Fix" to mapOf("Provider" to 1,"LatitudeDegrees" to 2,"LongitudeDegrees" to 3,"SpeedMps" to 5,
        "AccuracyMeters" to 6,"BearingDegrees" to 7,"UnixTimeMillis" to 8,"SpeedAccuracyMps" to 9,
        "BearingAccuracyDegrees" to 10,"elapsedRealtimeNanos" to 11,"MockLocation" to 13),
      "Status" to mapOf("UnixTimeMillis" to 1,"SignalCount" to 2,"SignalIndex" to 3,"ConstellationType" to 4,
        "Svid" to 5,"CarrierFrequencyHz" to 6,"Cn0DbHz" to 7,"AzimuthDegrees" to 8,"ElevationDegrees" to 9,
        "UsedInFix" to 10,"BasebandCn0DbHz" to 13),
      "Agc" to mapOf("utcTimeMillis" to 1,"AgcDb" to 11,"CarrierFrequencyHz" to 12,"ConstellationType" to 13),
      "Accel" to mapOf("elapsedRealtimeNanos" to 2,"AccelXMps2" to 3,"AccelYMps2" to 4,"AccelZMps2" to 5),
      "UncalAccel" to mapOf("elapsedRealtimeNanos" to 2,"UncalAccelXMps2" to 3,"UncalAccelYMps2" to 4,
        "UncalAccelZMps2" to 5,"BiasXMps2" to 6,"BiasYMps2" to 7,"BiasZMps2" to 8),
      "Gyro" to mapOf("elapsedRealtimeNanos" to 2,"GyroXRadPerSec" to 3,"GyroYRadPerSec" to 4,"GyroZRadPerSec" to 5),
      "UncalGyro" to mapOf("elapsedRealtimeNanos" to 2,"UncalGyroXRadPerSec" to 3,"UncalGyroYRadPerSec" to 4,
        "UncalGyroZRadPerSec" to 5,"DriftXRadPerSec" to 6,"DriftYRadPerSec" to 7,"DriftZRadPerSec" to 8),
      "Mag" to mapOf("elapsedRealtimeNanos" to 2,"MagXMicroT" to 3,"MagYMicroT" to 4,"MagZMicroT" to 5),
      "UncalMag" to mapOf("elapsedRealtimeNanos" to 2,"UncalMagXMicroT" to 3,"UncalMagYMicroT" to 4,
        "UncalMagZMicroT" to 5,"BiasXMicroT" to 6,"BiasYMicroT" to 7,"BiasZMicroT" to 8),
      "OrientationDeg" to mapOf("elapsedRealtimeNanos" to 2,"yawDeg" to 3,"rollDeg" to 4,"pitchDeg" to 5)
    )

    fun decode(text:String):GnssLoggerImportResult {
        if(text.toByteArray(Charsets.UTF_8).size>MAX_IMPORT_BYTES)return fail("GnssLogger CSV превышает лимит 2 МиБ.")
        val lines=text.lineSequence().toList()
        if(lines.size>MAX_IMPORT_LINES)return fail("В GnssLogger CSV слишком много строк.")
        val parsed=parse(lines);val rows=parsed.rows;val h=parsed.headers;var skipped=parsed.skipped
        val raw=rows.filter{it.type=="Raw"};val fixes=rows.filter{it.type=="Fix"}
        val statuses=rows.filter{it.type=="Status"};val agcRows=rows.filter{it.type=="Agc"}
        val sensorRows=rows.filter{it.type in setOf("Accel","UncalAccel","Gyro","UncalGyro","Mag","UncalMag","OrientationDeg")}
        if(raw.isEmpty()&&fixes.isEmpty()&&statuses.isEmpty()&&agcRows.isEmpty())
            return fail("Не найдены поддерживаемые записи Raw, Fix, Status или Agc.",skipped)

        val bootOffset=rows.firstNotNullOfOrNull { row ->
            val elapsed=elapsed(row,h);val utc=utcMillis(row,h)
            if(elapsed!=null&&utc!=null)msToNs(utc)?.let{elapsed-it}else null
        }
        val times=rowTimes(rows,h,bootOffset)
        val pending=mutableListOf<Pending>();val validFixes=mutableListOf<Fix>()
        fixes.forEach { row ->
            val provider=value(row,"Provider",h)?.uppercase()?:run{skipped++;return@forEach}
            val source=when(provider){"GPS"->GnssReplayPositionSource.GPS;"NLP","NETWORK","NETWORK_PROVIDER","FLP"->GnssReplayPositionSource.NETWORK;else->{skipped++;return@forEach}}
            if(bool(value(row,"MockLocation",h))){skipped++;return@forEach}
            val lat=num(row,"LatitudeDegrees",h)?:run{skipped++;return@forEach}
            val lon=num(row,"LongitudeDegrees",h)?:run{skipped++;return@forEach}
            if(lat !in -90.0..90.0||lon !in -180.0..180.0){skipped++;return@forEach}
            validFixes+=Fix(row,source,lat,lon,num(row,"AccuracyMeters",h)?.takeIf{it>=0.0}?:1000.0,
                num(row,"SpeedMps",h)?.takeIf{it>=0.0},num(row,"SpeedAccuracyMps",h)?.takeIf{it>=0.0},
                num(row,"BearingDegrees",h)?.let{bearing(it)},num(row,"BearingAccuracyDegrees",h)?.takeIf{it>=0.0})
        }
        val origin=validFixes.filter{it.source==GnssReplayPositionSource.GPS}.minByOrNull{times[it.row.line]?:0L}
            ?:validFixes.minByOrNull{times[it.row.line]?:0L}
        validFixes.forEach { fix ->
            val base=origin?:run{skipped++;return@forEach}
            val east=Math.toRadians(lonDelta(fix.lon-base.lon))*EARTH_RADIUS*cos(Math.toRadians(base.lat))
            val north=Math.toRadians(fix.lat-base.lat)*EARTH_RADIUS
            pending+=Pending(times[fix.row.line]?:0L,fix.row.line,GnssReplayEvent.Position(0L,fix.source,east,north,
                fix.acc,fix.speed,fix.speedAcc,fix.bearing,fix.bearingAcc,false,0,"NORMAL",
                if(fix.source==GnssReplayPositionSource.GPS)"GPS" else "NETWORK"))
        }

        val statusGroups=linkedMapOf<String,MutableList<Row>>();var batch=0;var blankRemaining=0
        statuses.forEach { row ->
            val utc=value(row,"UnixTimeMillis",h)?.toLongOrNull()
            val key=if(utc!=null&&utc>0L)"utc:$utc" else {
                val index=int(row,"SignalIndex",h)?:-1
                if(index==0||blankRemaining<=0){batch++;blankRemaining=(int(row,"SignalCount",h)?:1).coerceIn(1,512)}
                blankRemaining--;"blank:$batch"
            }
            statusGroups.getOrPut(key){mutableListOf()}.add(row)
        }
        statusGroups.values.forEach { group ->
            val all=mutableListOf<GnssReplaySatellite>()
            group.forEach satLoop@{ row ->
                val sv=int(row,"Svid",h);val constellation=int(row,"ConstellationType",h)?:0
                if(sv==null||sv !in 1..4096||constellation !in 0..10){skipped++;return@satLoop}
                all+=GnssReplaySatellite(constellation,sv,num(row,"Cn0DbHz",h)?.takeIf{it in 0.0..100.0},
                    num(row,"ElevationDegrees",h)?.takeIf{it in -90.0..90.0},
                    num(row,"AzimuthDegrees",h)?.let{bearing(it)},bool(value(row,"UsedInFix",h)),
                    num(row,"CarrierFrequencyHz",h)?.takeIf{it in 1.0..100_000_000_000.0},
                    num(row,"BasebandCn0DbHz",h)?.takeIf{it in 0.0..100.0})
            }
            if(all.isEmpty()){skipped+=group.size;return@forEach}
            val sats=all.take(512);if(all.size>sats.size)skipped+=all.size-sats.size
            val cn0=sats.mapNotNull{it.cn0DbHz?.takeIf{v->v>0.0}}
            val mean=cn0.average().takeIf{cn0.isNotEmpty()&&it.isFinite()}
            val spread=mean?.let{m->if(cn0.size<2)null else sqrt(cn0.sumOf{(it-m)*(it-m)}/cn0.size)}
            val count=group.mapNotNull{int(it,"SignalCount",h)}.maxOrNull()?:sats.size
            val snap=GnssSignalSnapshot(1L,maxOf(sats.size,count.coerceAtMost(512)),sats.count{it.usedInFix},mean,spread,
                sats.map{it.constellationType}.filter{it>0}.distinct().size,cn0.count{it>=32.0})
            val first=group.first()
            pending+=Pending(times[first.line]?:0L,first.line,GnssReplayEvent.Signal(0L,snap,sats))
        }

        val agc=agcRows.mapNotNull { row ->
            val db=num(row,"AgcDb",h)?.takeIf{it in -200.0..200.0}?:run{skipped++;return@mapNotNull null}
            Agc(row,times[row.line]?:0L,int(row,"ConstellationType",h)?.coerceIn(0,10)?:0,
                num(row,"CarrierFrequencyHz",h)?.takeIf{it>0.0},db)
        }
        val rawGroups=raw.groupBy{times[it.line]?:0L}
        rawGroups.forEach { (time,group) ->
            val measurements=group.mapNotNull { row ->
                val sv=int(row,"Svid",h);val constellation=int(row,"ConstellationType",h)?:0
                if(sv==null||sv !in 1..4096||constellation !in 0..10){skipped++;return@mapNotNull null}
                val hz=num(row,"CarrierFrequencyHz",h)?.takeIf{it in 1.0..100_000_000_000.0}
                val db=num(row,"AgcDb",h)?.takeIf{it in -200.0..200.0} ?: agc.asSequence()
                    .filter{abs(it.t-time)<=2_000_000_000L&&it.constellation==constellation}
                    .filter{hz==null||it.hz==null||abs(it.hz-hz)<1.0}.minByOrNull{abs(it.t-time)}?.db
                GnssReplayMeasurement(constellation,sv,num(row,"Cn0DbHz",h)?.takeIf{it in 0.0..100.0},hz,db,
                    num(row,"PseudorangeRateMetersPerSecond",h)?.takeIf{it in -100_000.0..100_000.0},
                    num(row,"PseudorangeRateUncertaintyMetersPerSecond",h)?.takeIf{it in 0.0..100_000.0},
                    long(row,"ReceivedSvTimeNanos",h)?.takeIf{it in 0L..1_000_000_000_000_000L},
                    long(row,"ReceivedSvTimeUncertaintyNanos",h)?.takeIf{it in 0L..1_000_000_000_000_000L},
                    int(row,"State",h)?.coerceAtLeast(0)?:0)
            }.take(512)
            if(measurements.isEmpty()){skipped+=group.size;return@forEach}
            if(group.size>measurements.size)skipped+=group.size-measurements.size
            val cn0=measurements.mapNotNull{it.cn0DbHz?.takeIf{v->v>0.0}};val gains=measurements.mapNotNull{it.agcDb}
            val snap=GnssRfSnapshot(1L,measurements.size,measurements.map{it.constellationType}.distinct().size,
                cn0.average().takeIf{cn0.isNotEmpty()&&it.isFinite()},
                gains.average().takeIf{gains.isNotEmpty()&&it.isFinite()},gains.size)
            pending+=Pending(time,group.minOf{it.line},GnssReplayEvent.Rf(0L,snap,measurements))
        }
        agc.groupBy{it.t}.forEach { (time,group) ->
            if(rawGroups.keys.any{abs(it-time)<=2_000_000_000L})return@forEach
            val rowsAtTime=group.take(512);val gains=rowsAtTime.map{it.db}
            pending+=Pending(time,group.minOf{it.row.line},GnssReplayEvent.Rf(0L,
                GnssRfSnapshot(1L,rowsAtTime.size,rowsAtTime.map{it.constellation}.distinct().size,null,
                    gains.average().takeIf{it.isFinite()},gains.size),emptyList()))
        }
        var sensors=0
        sensorRows.forEach { row ->
            val e=sensor(row,h)
            if(e==null){skipped++;return@forEach}
            sensors++;pending+=Pending(times[row.line]?:0L,row.line,e)
        }
        if(pending.isEmpty())return fail("В GnssLogger CSV не удалось разобрать события.",skipped)
        if(pending.size>GnssReplayCodec.MAX_EVENTS)return fail("Больше 25 000 событий; сократи исходный лог.",skipped)
        val ordered=pending.sortedWith(compareBy<Pending>{it.t}.thenBy{it.order})
        val first=ordered.first().t;val last=ordered.last().t
        if(last<first||last-first>GnssReplayCodec.MAX_DURATION_NANOS)return fail("Длительность лога превышает семь суток.",skipped)
        var previous=0L
        val events=ordered.map { p ->
            val offset=(p.t-first).coerceIn(0L,GnssReplayCodec.MAX_DURATION_NANOS).coerceAtLeast(previous)
            previous=offset;rebase(p.e,offset)
        }
        return GnssLoggerImportResult(GnssReplayDecodeResult(events,skipped,false),
            GnssLoggerImportStats(raw.size,fixes.size,statuses.size,agcRows.size,sensors,events.size,skipped))
    }

    private fun parse(lines:List<String>):Parsed {
        val headers=mutableMapOf<String,Map<String,Int>>();val rows=mutableListOf<Row>();var skipped=0
        lines.forEachIndexed { i,original ->
            val line=original.removePrefix("\uFEFF").trimEnd('\r')
            if(line.isBlank())return@forEachIndexed
            if(line.trimStart().startsWith("#")) {
                val f=csv(line.trimStart().removePrefix("#").trimStart())?:return@forEachIndexed
                val type=f.firstOrNull()?.trim()
                if(type!=null&&type in supported&&f.size>1)headers[type]=f.mapIndexedNotNull{idx,name->name.trim().takeIf{it.isNotEmpty()}?.let{it to idx}}.toMap()
                return@forEachIndexed
            }
            val f=csv(line)
            if(f==null){skipped++;return@forEachIndexed}
            val type=f.firstOrNull()?.trim().orEmpty()
            if(type in supported)rows+=Row(type,f,i+1) else skipped++
        }
        return Parsed(rows,headers,skipped)
    }
    private fun csv(s:String):List<String>? {
        val out=mutableListOf<String>();val cell=StringBuilder();var quote=false;var i=0
        while(i<s.length){val c=s[i];when{
            c=='"'&&quote&&i+1<s.length&&s[i+1]=='"'->{cell.append('"');i++}
            c=='"'->quote=!quote
            c==','&&!quote->{out+=cell.toString();cell.setLength(0)}
            else->cell.append(c)
        };i++}
        if(quote)return null
        out+=cell.toString();return out
    }
    private fun value(r:Row,name:String,h:Map<String,Map<String,Int>>):String? {
        val idx=h[r.type]?.get(name)?:columns[r.type]?.get(name)?:return null
        return r.f.getOrNull(idx)?.trim()?.takeIf{it.isNotEmpty()}
    }
    private fun num(r:Row,n:String,h:Map<String,Map<String,Int>>)=value(r,n,h)?.toDoubleOrNull()?.takeIf{it.isFinite()}
    private fun long(r:Row,n:String,h:Map<String,Map<String,Int>>)=value(r,n,h)?.toLongOrNull()
    private fun int(r:Row,n:String,h:Map<String,Map<String,Int>>)=value(r,n,h)?.toIntOrNull()
    private fun bool(s:String?)=s?.trim()?.lowercase() in setOf("1","true","yes")
    private fun elapsed(r:Row,h:Map<String,Map<String,Int>>)=long(r,if(r.type=="Raw")"ChipsetElapsedRealtimeNanos" else "elapsedRealtimeNanos",h)?.takeIf{it>0L}
    private fun utcMillis(r:Row,h:Map<String,Map<String,Int>>)=long(r,if(r.type=="Fix"||r.type=="Status")"UnixTimeMillis" else "utcTimeMillis",h)?.takeIf{it>0L}
    private fun msToNs(ms:Long)=if(ms in 1L..9_000_000_000_000L)ms*1_000_000L else null
    private fun rowTimes(rows:List<Row>,h:Map<String,Map<String,Int>>,boot:Long?):Map<Int,Long> {
        val actual=rows.associate{r->val e=elapsed(r,h);val u=utcMillis(r,h)?.let{msToNs(it)}
            r.line to when{e!=null->e;u!=null->runCatching{Math.addExact(u,boot?:0L)}.getOrNull();else->null}}
        val result=mutableMapOf<Int,Long>();var prior:Long?=null
        rows.forEach{r->val t=actual[r.line];if(t!=null)prior=t;if(t!=null||prior!=null)result[r.line]=t?:prior!!}
        var next:Long?=null
        rows.asReversed().forEach{r->val t=actual[r.line];if(t!=null)next=t;if(r.line !in result&&next!=null)result[r.line]=next!!}
        rows.forEach{if(it.line !in result)result[it.line]=it.line.toLong()*1_000_000L}
        return result
    }
    private fun sensor(r:Row,h:Map<String,Map<String,Int>>):GnssReplayEvent.Sensor? {
        val type:GnssReplaySensorType;val calibrated:Boolean;val x:String;val y:String;val z:String
        val bx:String?;val by:String?;val bz:String?
        when(r.type){
            "Accel"->{type=GnssReplaySensorType.ACCELEROMETER;calibrated=true;x="AccelXMps2";y="AccelYMps2";z="AccelZMps2";bx=null;by=null;bz=null}
            "UncalAccel"->{type=GnssReplaySensorType.ACCELEROMETER;calibrated=false;x="UncalAccelXMps2";y="UncalAccelYMps2";z="UncalAccelZMps2";bx="BiasXMps2";by="BiasYMps2";bz="BiasZMps2"}
            "Gyro"->{type=GnssReplaySensorType.GYROSCOPE;calibrated=true;x="GyroXRadPerSec";y="GyroYRadPerSec";z="GyroZRadPerSec";bx=null;by=null;bz=null}
            "UncalGyro"->{type=GnssReplaySensorType.GYROSCOPE;calibrated=false;x="UncalGyroXRadPerSec";y="UncalGyroYRadPerSec";z="UncalGyroZRadPerSec";bx="DriftXRadPerSec";by="DriftYRadPerSec";bz="DriftZRadPerSec"}
            "Mag"->{type=GnssReplaySensorType.MAGNETOMETER;calibrated=true;x="MagXMicroT";y="MagYMicroT";z="MagZMicroT";bx=null;by=null;bz=null}
            "UncalMag"->{type=GnssReplaySensorType.MAGNETOMETER;calibrated=false;x="UncalMagXMicroT";y="UncalMagYMicroT";z="UncalMagZMicroT";bx="BiasXMicroT";by="BiasYMicroT";bz="BiasZMicroT"}
            "OrientationDeg"->{type=GnssReplaySensorType.ORIENTATION_DEGREES;calibrated=true;x="yawDeg";y="rollDeg";z="pitchDeg";bx=null;by=null;bz=null}
            else->return null
        }
        val xv=num(r,x,h)?:return null;val yv=num(r,y,h)?:return null;val zv=num(r,z,h)?:return null
        return runCatching{GnssReplayEvent.Sensor(0L,type,calibrated,
            if(type==GnssReplaySensorType.ORIENTATION_DEGREES)bearing(xv)else xv,yv,zv,
            bx?.let{num(r,it,h)},by?.let{num(r,it,h)},bz?.let{num(r,it,h)})}.getOrNull()
    }
    private fun rebase(e:GnssReplayEvent,t:Long):GnssReplayEvent=when(e){
        is GnssReplayEvent.Signal->e.copy(offsetNanos=t,snapshot=e.snapshot.copy(elapsedRealtimeNanos=t+1L))
        is GnssReplayEvent.Rf->e.copy(offsetNanos=t,snapshot=e.snapshot.copy(elapsedRealtimeNanos=t+1L))
        is GnssReplayEvent.Sensor->e.copy(offsetNanos=t)
        is GnssReplayEvent.RadioSnapshot->e.copy(offsetNanos=t)
        is GnssReplayEvent.Position->e.copy(offsetNanos=t)
    }
    private fun lonDelta(v:Double):Double {var d=v;while(d>180)d-=360.0;while(d< -180)d+=360.0;return d}
    private fun bearing(v:Double):Double {val d=v%360;return if(d<0)d+360 else d}
    private fun fail(message:String,skipped:Int=0)=GnssLoggerImportResult(
        GnssReplayDecodeResult(emptyList(),skipped,false,message),GnssLoggerImportStats(skippedRows=skipped))
}
