package com.yu.retailops.tool;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Streaming physical-line bounds and timestamp-delimited events. Thread reuse is not correlation. */
final class LogEventReader {
    static final Pattern HEADER=Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{3})?).*");
    static final Pattern TRACE=Pattern.compile("(?i)\\btraceId[=: ]+([A-Za-z0-9_-]{4,64})\\b");
    record Event(long start,long end,String time,String text,boolean truncated) {}
    record Scan(List<Event> events,boolean truncated,int characters) {}
    static Scan read(Reader reader,int budget) throws IOException {
        List<Event> events=new ArrayList<>();StringBuilder line=new StringBuilder(),event=new StringBuilder();
        long number=1,start=1,end=0;String time="";boolean cut=false,lineCut=false,scanCut=false;int count=0,c;
        while((c=reader.read())!=-1) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            if(++count>budget){scanCut=true;cut=true;break;}
            if(c!='\n') {if(line.length()<32768)line.append((char)c);else lineCut=true;continue;}
            String text=line.toString();var header=HEADER.matcher(text);
            if(header.matches()) {
                if(event.length()>0)events.add(new Event(start,end,time,event.toString(),cut));
                if(events.size()>=10000){scanCut=true;event.setLength(0);break;}
                start=number;time=header.group(1);event.setLength(0);cut=false;
            }
            if(event.length()+text.length()+1<=8192)event.append(text).append('\n');else {event.append(text,0,Math.max(0,8192-event.length()));cut=true;}
            cut|=lineCut;scanCut|=lineCut;end=number++;
            line.setLength(0);lineCut=false;
        }
        if(line.length()>0) {
            String text=line.toString();var header=HEADER.matcher(text);
            if(header.matches()) {
                if(event.length()>0)events.add(new Event(start,end,time,event.toString(),cut));
                start=number;time=header.group(1);event.setLength(0);cut=false;
            }
            if(event.length()+text.length()<=8192)event.append(text);else {event.append(text,0,Math.max(0,8192-event.length()));cut=true;}
            cut|=lineCut||scanCut;end=number;
        }
        if(event.length()>0)events.add(new Event(start,end,time,event.toString(),cut));
        return new Scan(List.copyOf(events),scanCut||events.stream().anyMatch(Event::truncated),count);
    }
}
