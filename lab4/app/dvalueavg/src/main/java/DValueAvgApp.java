import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.KeyValueTextInputFormat;
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class DValueAvgApp {
    private final static String DELIMITER = ",";
    private final static String TAB_DELIMITER = "\t";
    private final static String NULL_SYMBOL = "\\N";
    private final static String DEVICE_TABLE_TAG = "DEV";
    private final static String DVALUES_TABLE_TAG = "DVAL";

    private final static String FS_DEFAULTFS="hdfs://hadoopmaster:8020";
    private final static String TEMP_FILE_PATH = "/tmp/dvaluesavg";

    private static FileSystem hdfs = null;

    public static class DeviceJoinMapper
            extends Mapper<Object, Text, IntWritable, Text> {

        private String[] fields;
        private IntWritable outputKey = new IntWritable(0);
        private Text outputValue = new Text();

        @Override
        protected void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            fields = value.toString().split(DELIMITER);
            // Key format is id
            outputKey.set(Integer.valueOf(fields[0]));
            // Value format is DEV,type
            outputValue.set(DEVICE_TABLE_TAG + DELIMITER + fields[1]);
            context.write(outputKey, outputValue);
        }
    }

    public static class DValueJoinMapper
            extends Mapper<Object, Text, IntWritable, Text> {

        private String[] fields;
        private IntWritable outputKey = new IntWritable();
        private Text outputValue = new Text();

        @Override
        protected void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            fields = value.toString().split(DELIMITER);
            Integer did = Integer.valueOf(fields[0]);
            // Filter out irrelevant records
            if (did > 0 && did < 10 && !fields[1].equals(NULL_SYMBOL)) {
                // Key format is did
                outputKey.set(did);
                // Value format is DVAL,date,value
                outputValue.set(DVALUES_TABLE_TAG + DELIMITER + fields[1] + DELIMITER +fields[2]);
                context.write(outputKey, outputValue);
            }
        }
    }

    public static class JoinReducer
            extends Reducer<IntWritable, Text, Text, Text> {
        private String[] fields;
        private List<String[]> deviceFieldsList = new ArrayList<>();
        private List<String[]> dValuesFieldsList = new ArrayList<>();

        private Text outputKey = new Text();
        private Text outputValue = new Text();

        @Override
        protected void reduce(IntWritable key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
            deviceFieldsList.clear();
            dValuesFieldsList.clear();
            for(Text value: values) {
                fields = value.toString().split(DELIMITER);
                if(fields[0].equals(DEVICE_TABLE_TAG)) {
                    deviceFieldsList.add(fields);
                }
                else {
                    dValuesFieldsList.add(fields);
                }
            }

            for (String[] deviceFields: deviceFieldsList) {
                for(String[] dValuesFields: dValuesFieldsList) {
                    outputKey.set(dValuesFields[1] + DELIMITER + deviceFields[1]);
                    outputValue.set(dValuesFields[2]);
                    context.write(outputKey, outputValue);
                }
            }
        }
    }

    public static class GroupMapper
            extends Mapper<Text, Text, Text, DoubleWritable> {
        private DoubleWritable outputValue = new DoubleWritable();
        @Override
        protected void map(Text key, Text value, Context context) throws IOException, InterruptedException {
            outputValue.set(Double.valueOf(value.toString()));
            context.write(key, outputValue);
        }
    }

    public static class SumReducer
            extends Reducer<Text, DoubleWritable, Text, DoubleWritable> {

        private Text outputKey = new Text();
        private DoubleWritable outputValue = new DoubleWritable();

        @Override
        protected void reduce(Text key, Iterable<DoubleWritable> values, Context context) throws IOException, InterruptedException {
            double sum = 0;
            int count = 0;
            for(DoubleWritable value: values) {
                sum += value.get();
                count++;
            }
            outputValue.set(sum / count);
            outputKey.set(key.toString().replace(DELIMITER, TAB_DELIMITER));
            context.write(outputKey, outputValue);
        }
    }

    private static FileSystem getHDFS(Configuration conf) throws Exception{
        if (hdfs == null) {
            hdfs = FileSystem.get(URI.create(FS_DEFAULTFS), conf);
        }
        return hdfs;
    }

    public static class DescendingKeyComparator extends WritableComparator {
        protected DescendingKeyComparator() {
            super(Text.class, true);
        }

        @SuppressWarnings("rawtypes")
        @Override
        public int compare(WritableComparable w1, WritableComparable w2) {
            Text key1 = (Text) w1;
            Text key2 = (Text) w2;
            String keyfields1[] = key1.toString().split(DELIMITER);
            String keyfields2[] = key2.toString().split(DELIMITER);
            if (keyfields1[0].equals(keyfields2[0])) {
                return keyfields1[1].compareTo(keyfields2[1]);
            } else {
                return -1 * keyfields1[0].compareTo(keyfields2[0]);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usages: DValueSumApp <device.txt path> <dvalues.txt path> <output path>");
            System.exit(-1);
        }

        Configuration conf = new Configuration();
        // Clear output path and temp path
        getHDFS(conf).delete(new Path(args[2]), true);
        getHDFS(conf).delete(new Path(TEMP_FILE_PATH), true);

        // Join Job
        Job joinJob = Job.getInstance(conf, "Device and DValue Join Job");
        joinJob.setJarByClass(DValueAvgApp.class);
        MultipleInputs.addInputPath(joinJob, new Path(args[0]), TextInputFormat.class, DeviceJoinMapper.class);
        MultipleInputs.addInputPath(joinJob, new Path(args[1]), TextInputFormat.class, DValueJoinMapper.class);
        joinJob.setOutputValueClass(KeyValueTextInputFormat.class);
        joinJob.setMapOutputKeyClass(IntWritable.class);
        joinJob.setMapOutputValueClass(Text.class);
        joinJob.setReducerClass(JoinReducer.class);
        joinJob.setOutputKeyClass(Text.class);
        joinJob.setOutputValueClass(Text.class);
        FileOutputFormat.setOutputPath(joinJob, new Path(TEMP_FILE_PATH));
        if (!joinJob.waitForCompletion(true)) {
            getHDFS(conf).delete(new Path(TEMP_FILE_PATH), true);
            getHDFS(conf).close();
            System.exit(1);
        }

        // Avg Job
        Job avgJob = Job.getInstance(conf, "Device and DValue Avg Job");
        avgJob.setJarByClass(DValueAvgApp.class);
        avgJob.setMapperClass(GroupMapper.class);
        avgJob.setCombinerClass(SumReducer.class);
        avgJob.setReducerClass(SumReducer.class);
        avgJob.setSortComparatorClass(DescendingKeyComparator.class);
        avgJob.setInputFormatClass(KeyValueTextInputFormat.class);
        avgJob.setMapOutputKeyClass(Text.class);
        avgJob.setMapOutputValueClass(DoubleWritable.class);
        avgJob.setOutputKeyClass(Text.class);
        avgJob.setOutputValueClass(DoubleWritable.class);
        FileInputFormat.addInputPath(avgJob, new Path(TEMP_FILE_PATH + "/*"));
        FileOutputFormat.setOutputPath(avgJob, new Path(args[2]));

        boolean code = avgJob.waitForCompletion(true);
        getHDFS(conf).delete(new Path(TEMP_FILE_PATH), true);
        getHDFS(conf).close();
        System.exit(code ? 0 : 1);
    }
}