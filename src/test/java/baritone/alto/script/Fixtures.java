/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.alto.script;

import static baritone.alto.script.Scripts.j;

/**
 * Shared test scripts.
 */
final class Fixtures {

    /** Uses every block, expression, event and target kind once; valid (warnings only). */
    static final String EVERYTHING = j("""
            {'format':'alto-script','version':1,'name':'everything','description':'all blocks',
             'variables':[{'name':'count','initial':0},{'name':'label','initial':'x'},{'name':'flag','initial':true}],
             'procedures':[{'name':'restock','params':[{'name':'item','type':'item'},{'name':'amount','type':'number'}],
               'body':[{'block':'if','condition':{'expr':'lt','a':{'expr':'item_count','item':{'expr':'param','name':'item'},'where':'inventory'},
                                                  'b':{'expr':'param','name':'amount'}},
                        'then':[{'block':'fetch','item':{'expr':'param','name':'item'},'count':{'expr':'param','name':'amount'}}]}]}],
             'events':[
               {'event':'on_start','body':[
                  {'block':'goto','target':{'type':'coords','x':1,'y':64,'z':-3,'dimension':'minecraft:overworld'}},
                  {'block':'goto','target':{'type':'home','name':'base'}},
                  {'block':'goto','target':{'type':'storage_zone','name':'main'}},
                  {'block':'goto','target':{'type':'portal'}},
                  {'block':'mine','block_or_item':'minecraft:coal_ore','count':16},
                  {'block':'build','schematic':'house.schem','x':0,'y':64,'z':0},
                  {'block':'fill','material':'minecraft:stone','x1':0,'y1':60,'z1':0,'x2':2,'y2':60,'z2':2},
                  {'block':'farm','range':16,'crops':['minecraft:wheat']},
                  {'block':'craft','item':'oak_planks','count':4},
                  {'block':'smelt','item':'iron_ingot','count':8},
                  {'block':'fetch','item':'bread','count':4},
                  {'block':'deposit','except':['minecraft:bread'],'only':['minecraft:cobblestone']},
                  {'block':'eat'},
                  {'block':'wait','seconds':0.5},
                  {'block':'message','text':{'expr':'join','parts':['count=',{'expr':'var','name':'count'},' ',
                      {'expr':'hunger'},{'expr':'position','axis':'y'},{'expr':'time_of_day'},{'expr':'dimension'},
                      {'expr':'block_at','x':0,'y':63,'z':0},{'expr':'inventory_full'},
                      {'expr':'sub','a':5,'b':2},{'expr':'mul','a':5,'b':2},{'expr':'div','a':5,'b':2},
                      {'expr':'mod','a':5,'b':2},{'expr':'min','a':5,'b':2},{'expr':'max','a':5,'b':2},
                      {'expr':'eq','a':'a','b':'a'},{'expr':'neq','a':1,'b':2},{'expr':'lte','a':1,'b':2},
                      {'expr':'or','a':false,'b':true}]}},
                  {'block':'command','command':'mine diamond_ore'},
                  {'block':'send_chat','text':'hello'},
                  {'block':'use_item_on_block','item':'bone_meal','x':1,'y':64,'z':1,'face':'up'},
                  {'block':'set_filter','x':5,'y':64,'z':5,'face':'north','item':'iron_ingot'},
                  {'block':'place_station','kind':'furnace'},
                  {'block':'pickup_station','kind':'furnace'},
                  {'block':'board_vehicle','kind':'boat'},
                  {'block':'ride_train','from_station':'A','to_station':'B'},
                  {'block':'leave_vehicle'},
                  {'block':'take_portal'},
                  {'block':'repeat','times':2,'body':[{'block':'wait','seconds':1}]},
                  {'block':'repeat_until','condition':{'expr':'gte','a':{'expr':'var','name':'count'},'b':3},
                   'body':[{'block':'change_var','name':'count','delta':1},{'block':'wait','seconds':0}]},
                  {'block':'forever','body':[{'block':'wait','seconds':1},{'block':'break'}]},
                  {'block':'if','condition':{'expr':'and','a':{'expr':'var','name':'flag'},'b':{'expr':'not','a':false}},
                   'then':[{'block':'set_var','name':'label','value':'y'}],
                   'else':[{'block':'set_var','name':'flag','value':false}]},
                  {'block':'wait_until','condition':{'expr':'gt','a':{'expr':'health'},'b':5},'timeout_seconds':30},
                  {'block':'call','procedure':'restock','args':{'item':'minecraft:oak_log','amount':8}},
                  {'block':'set_var','name':'count','value':{'expr':'add','a':{'expr':'random','min':1,'max':6},
                     'b':{'expr':'round','a':{'expr':'abs','a':-2.5}}}},
                  {'block':'stop'}
               ]},
               {'event':'on_chat','trigger':'!go','body':[{'block':'message','text':'go'}]},
               {'event':'on_inventory_full','body':[{'block':'deposit'}]},
               {'event':'on_health_below','health':8,'body':[{'block':'eat'}]},
               {'event':'on_item_below','item':'bread','count':4,'where':'inventory','body':[{'block':'fetch','item':'bread','count':16}]},
               {'event':'every','seconds':300,'body':[{'block':'farm'}]}
             ]}""");

    private Fixtures() {
    }
}
