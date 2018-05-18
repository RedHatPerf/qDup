package perf.qdup.cmd.impl;

import perf.qdup.cmd.Cmd;
import perf.qdup.cmd.CommandResult;
import perf.qdup.cmd.Context;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ForEach extends Cmd {

    private String name;
    private String input;
    private List<String> split;
    private int index;

    public ForEach(String name){
        this(name,"");
    }
    public ForEach(String name,String input){
        this.name = name;
        this.input = "";
        this.split = null;
        this.index = -1;
    }

    @Override
    protected void run(String input, Context context, CommandResult result) {
        if(split == null){
            String toSplit = this.input.isEmpty() ? input : Cmd.populateStateVariables(this.input,this,context.getState());

            split = Collections.emptyList();

            if(toSplit.contains("\n")){
                split = Arrays.asList(toSplit.split("\n"));
            }else {
                //
            }
        }

        if(split!=null && !split.isEmpty()){
            String populatedName = Cmd.populateStateVariables(this.name,this,context.getState());
            index++;
            if(index < split.size()){
                String value = split.get(index);
                with(populatedName,value);
                result.next(this,value);
            }else{
                result.skip(this,input);
            }
        }
    }

    @Override
    protected Cmd clone() {
        return new ForEach(this.name,this.input).with(this.with);
    }

    @Override
    public Cmd then(Cmd command){
        Cmd currentTail = this.getTail();
        Cmd rtrn = super.then(command);
        currentTail.forceNext(command);
        command.forceNext(this);
        return rtrn;
    }

    @Override
    public String toString(){
        return "for-each: "+name+(this.input!=null?this.input:"");
    }
}