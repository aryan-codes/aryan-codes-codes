import logging
from threading import Thread
import time
import math
import serial
import heapq
import numpy as np
"""
import RPi.GPIO as GPIO

GPIO.setmode(GPIO.BCM)
GPIO.setwarnings(False)

GPIO.setup(23, GPIO.IN, pull_up_down = GPIO.PUD_DOWN)
GPIO.setup(24, GPIO.IN, pull_up_down = GPIO.PUD_UP)
GPIO.wait_for_edge(23, GPIO.FALLING)
GPIO.wait_for_edge(24, GPIO.FALLING)
"""
class CalculationAStarAlgorithm():
    def __init__(self, arena, start_charactor, goal_charactor):
        self.arena = arena
        self.start_charactor_position = self.getCharacotorCoordinates(start_charactor)
        self.goal_charactor_position = self.getCharacotorCoordinates(goal_charactor)

    def getCharacotorCoordinates(self, search_criteria_charactor):
        for index_height, line in enumerate(self.arena):
            for index_wedth, charactor in enumerate(line):
                if charactor == search_criteria_charactor:
                    return (index_height, index_wedth)

    def heuristic(self, position):
        return ((position[0] - self.goal_charactor_position[0]) ** 2 + (position[1] - self.goal_charactor_position[1]) ** 2) ** 0.5

    def distance(self, path):
        return len(path)

    def nextCandidatePosition(self, last_passed_position):
        wall = "1"
        vertical_axis, horizontal_axis = last_passed_position
        for next_vertical, next_horizontal in zip((vertical_axis + 1, vertical_axis - 1, vertical_axis, vertical_axis, vertical_axis + 1, vertical_axis + 1, vertical_axis - 1, vertical_axis - 1), (horizontal_axis, horizontal_axis, horizontal_axis + 1, horizontal_axis - 1, horizontal_axis + 1, horizontal_axis - 1, horizontal_axis - 1, horizontal_axis + 1)):
            if self.arena[next_vertical][next_horizontal] != wall:
                yield (next_vertical, next_horizontal)

    def aStarAlgorithm(self):
        passed_list = [self.start_charactor_position]
        init_score = self.distance(passed_list) + self.heuristic(self.start_charactor_position)
        checked = {self.start_charactor_position: init_score}
        searching_heap = []
        heapq.heappush(searching_heap, (init_score, passed_list))

        while len(searching_heap) > 0:
            score, passed_list = heapq.heappop(searching_heap)
            last_passed_position = passed_list[-1]

            if last_passed_position == self.goal_charactor_position:
                return passed_list
            for position in self.nextCandidatePosition(last_passed_position):
                new_passed_list = passed_list + [position]
                position_score = self.distance(new_passed_list) + self.heuristic(position)
                if position in checked and checked[position] <= position_score:
                    continue
                checked[position] = position_score
                heapq.heappush(searching_heap, (position_score, new_passed_list))
        return []

    def renderPath(self, path):
        structure = [[arena_dot for arena_dot in element] for element in self.arena]
        for dot in path[1:-1]:
            structure[dot[0]][dot[1]] = "$"
        structure[path[0][0]][path[0][1]] = "S"
        structure[path[-1][0]][path[-1][1]] = "G"
        return ["".join(l) for l in structure]

def state_space():
    state = ['111111111111111111111111111111111111111111111111']
    #print(len(state[0]))
    # establish state array
    for i in range(70):
        state.append('1                                              1')
    state.append('111111111111111111111111111111111111111111111111')
    return state

def change_state(state, cart1_coords, cart2_coords, ball_coords, goal_coords):
    #put goal position
    sample_str = state[ball_coords[1]]
    n = ball_coords[0]
    replacement = 'G'
    sample_str = sample_str[0:n] + replacement + sample_str[n+1: ]
    state[ball_coords[1]] = sample_str

    #put starting position
    sample_str = state[cart1_coords[1]]
    n = cart1_coords[0]
    replacement = 'S'
    sample_str = sample_str[0:n] + replacement + sample_str[n+1: ]
    state[cart1_coords[1]] = sample_str

    #cart 2 is an obstacle
    replacement = '1'
    for i in range(7):
        for j in range(7):
            sample_str = state[cart2_coords[1] - 4 + i]
            n = cart2_coords[0] - 4 + j
            sample_str = sample_str[0:n] + replacement + sample_str[n+1:]
            state[cart2_coords[1] - 4 + i] = sample_str
    #ball wall
    x_theta = ball_coords[0] - goal_coords[0]
    y_theta = ball_coords[1] - goal_coords[1]
    if x_theta == 0:
        theta = 90
    else:
        theta = math.atan(y_theta/x_theta)
    theta = math.degrees(theta)
    #print(theta)
    x_l, y_l = plot_circle(ball_coords[0], ball_coords[1], 4.5, theta)

    x_l = [round(num) for num in x_l]
    y_l = [round(num) for num in y_l]

    for i in range(len(x_l)):
        sample_str = state[y_l[i]]
        n = x_l[i]
        replacement = '1'
        sample_str = sample_str[0:n] + replacement + sample_str[n+1: ]
        state[y_l[i]] = sample_str

    sample_str = state[goal_coords[1]-1]
    n = goal_coords[0]
    replacement = 'Q'
    sample_str = sample_str[0:n] + replacement + sample_str[n+1: ]
    state[goal_coords[1]-1] = sample_str
    return state

def discretization(point):
    current_state = [int(round(point[0]/2)),int(round(point[1]/2))]
    return current_state

def plot_circle(x, y, radius, theta, color="-k"):
    start_degree = theta + 40
    end_degree = theta + 320
    deg = np.linspace(start_degree,end_degree,70)

    xl = [x + radius * math.cos(np.deg2rad(d)) for d in deg]
    yl = [y + radius * math.sin(np.deg2rad(d)) for d in deg]

    return xl, yl
"""
def dc_motors(action_sequence):
    serial_message = ""
    for i in action_sequence:
        #which way we are moving
        if i[0] == "left_forward":
            serial_message = serial_message + 'b1'
        elif i[0] == "forward":
            serial_message = serial_message + 'b2'
        elif i[0] == "right_forward":
            serial_message = serial_message + 'b3'
        elif i[0] == "right":
            serial_message = serial_message + 'b4'
        elif i[0] == "right_backward":
            serial_message = serial_message + 'b5'
        elif i[0] == "backward":
            serial_message = serial_message + 'b6'
        elif i[0] == "left_backward":
            serial_message = serial_message + 'b7'
        elif i[0] == "left":
            serial_message = serial_message + 'b8'
        else:
            serial_message = serial_message + 'b0'
        #how long the time step is
        serial_message = serial_message + " " + str(i[1]*.25) + " "

    return serial_message
"""
"""
def motion_planning(ballx_coord,bally_coord, cart2x_coord, cart2y_coord, cart_2_visible, robotx_coord = None, roboty_coord = None):
    collision = 0
    #this is if we are using pixy coords
    if (robotx_coord == None):
        distance_ball = math.sqrt(ballx_coord ** 2 + bally_coord ** 2)
        distance_cart2 = math.sqrt((cart2y_coord - bally_coord) ** 2 + (cart2x_coord - ballx_coord) ** 2)
        theta = math.atan(ballx_coord/bally_coord)
        x_distance = ballx_coord
        y_distance = bally_coord
    #using absolute coords
    else:
        distance_ball = math.sqrt((ballx_coord - robotx_coord) ** 2 + (bally_coord - robotx_coord) ** 2)
        distance_cart2 = math.sqrt((cart2y_coord - bally_coord) ** 2 + (cart2x_coord - ballx_coord) ** 2)
        theta = math.atan((ballx_coord - robotx_coord) / (bally_coord - roboty_coord))
        x_distance = ballx_coord - robotx_coord
        y_distance = bally_coord - roboty_coord
    if cart_2_visible:
        pass
    else:
        distance_cart2 = float('inf')

    theta = math.degrees(theta)

    closer = False
    #test to see if we are closer
    if (distance_cart2 - distance_ball) < distance_ball:
        closer = True





    if 0 <= theta <= 25:
        action_sequence = [["right_forward",x_distance/2],["right",x_distance/2],["forward",y_distance - x_distance/2]]
    elif 25 <= theta <= 45:
        #move right then forward
        action_sequence = [["right",x_distance],["forward",y_distance]]
    elif 45 <= theta <= 90:
        #move back and right then forward
        action_sequence = [["right_backward",x_distance/2],["right",x_distance/2],["forward",y_distance + x_distance/2]]
    elif 0 >= theta >= -25:
        #move left then forward
        action_sequence = [["left",x_distance],["forward",y_distance]]
    elif -25 >= theta >= -45:
        #move left then forward
        action_sequence = [["left",x_distance],["forward",y_distance]]
    elif -45 >= theta >= -90:
        #move back and left then forward
        action_sequence = [["left_backward",x_distance/2],["left",x_distance/2],["forward",y_distance + x_distance/2]]
    else:
        #move directly backward
        action_sequence = [["backward",y_distance]]
    return action_sequence
"""
"""
#returns ball x and y relative to car
def ball_coordinates(camera_x,camera_y,servo_theta1,servo_theta2):
    #camera outputs
    y = camera_y
    x = camera_x
    #change in angle cuz of camera
    alpha = math.atan(camera_x,camera_y)
    #angles in reference to camera(theta 1 is tilt up and down)
    theta1 = servo_theta1 + alpha
    theta2 = servo_theta2
    #lengths
    length_1 = math.sqrt(x ** 2 + y ** 2)
    length_2 = length_1 * math.cos(theta1)
    #coords
    y_coord = length_2 * math.cos(theta2)
    x_coord = length_2 * math.sin(theta2)
    return x_coord, y_coord
"""
def make_path(path):
    action_sequence = []
    for i in range(len(path)-1):
        y = path[i+1][0] - path[i][0]
        x = path[i][1] - path[i+1][1]
        action_sequence.append([x,y])
    return path, action_sequence

def action_sequence_condense(action_sequence):
    pop_list = []
    for i in range(len(action_sequence) - 1):
        unit_vector = []
        magnitude = (action_sequence[i + 1][0] ** 2 + action_sequence[i + 1][1] ** 2) ** .5
        unit_vector = [round(action_sequence[i + 1][0]/magnitude,2), round(action_sequence[i + 1][1]/magnitude,2)]

        magnitude2 = (action_sequence[i][0] ** 2 + action_sequence[i][1] ** 2) ** .5
        unit_vector2 = [round(action_sequence[i][0]/magnitude2,2), round(action_sequence[i][1]/magnitude2,2)]
        # if they are going in the same direction combine them
        if unit_vector == unit_vector2:
            action_sequence[i + 1][0] = action_sequence[i][0] + action_sequence[i + 1][0]
            action_sequence[i + 1][1] = action_sequence[i][1] + action_sequence[i + 1][1]
            pop_list.append(i)
    pop_list.reverse()
    #pop all duplicates
    for i in pop_list:
        action_sequence.pop(i)
    return action_sequence

def Three_scenarios(ball_coords, cart1_coords, cart2_coords, color):
    if color == "blue":
        goal1 = [24,72]
    else:
        goal1 = [24,0]

    """
    cart2_distance_goal = [cart2_coords[0] - goal1[0], cart2_coords[1] - goal1[1]]
    cart1_distance_goal = [cart1_coords[0] - goal1[0], cart1_coords[1] - goal1[1]]
    ball_distance_goal = [ball_coords[0] - goal1[0], ball_coords[1] - goal1[1]]
    theta_car1 = 1/math.tan(cart1_distance_goal[0],cart1_distance_goal)
    theta_car2 = 1/math.tan(cart2_distance_goal[0],cart2_distance_goal)
    theta_ball = 1/math.tan(ball_distance_goal[0],ball_distance_goal)
    """
    #other car is on a break away
    if cart2_distance_goal < cart1_distance_goal:
        pass
    # if other car is closer to ball

    #if we are closer to ball
    else:
        ball_coords = ball_coords
    return ball_coords

def Yaw_correction(yaw, goal_direction):
    #counter clockwise
    if  180 >= yaw - goal_direction >= 5:
        pass

    #clockwise
    elif 180 <= yaw - goal_direction <= 355:
        pass

    #straight
    else:
        pass

def button_function():
    while True:
        if GPIO.input(24) == True:
            print("hello")
        elif GPIO.input(23) == True:
            print("goodbye")
    return

def Parse(serial_message):
    serial_message = serial_message.split()
    return serial_message

def write_read(x, arduino):
    #x is action_sequence
    arduino.write(bytes(x, 'utf-8'))
    time.sleep(0.05)
    data = arduino.readline()
    return data

def action_seq_arduino(action_sequence):
    s=25
    for i in range(len(action_sequence)):
        a=int(action_sequence[i][0])
        b=int(action_sequence[i][1])
        if (a==0 & b>0):
            b=90
        elif (a==0 & b<0):
            b=270
        elif(a==0 & b==0):
            b=0
        else:
            b=int(math.degrees(math.atan(b/a)))
        print(b)
        if (b==90):
            a=0 #forward movement
        elif (b==45):
            a=1 #fwd right diagonal movement
        elif (b==135):
            a=2 #fwd left diagonal movement
        elif (b==180 or b==-180):
            a=3 #left movement
        elif (b==225 or b==-135):
            a=4 #bwd left movement
        elif (b==270 or b==-90):
            a=5 #bwd movement
        elif (b==315 or b==-45):
            a=6 #bwd right movement
        elif (b==0):
            a=7 #right movement
        action_sequence[i][0]=a
        mgvar=math.sqrt((a* a)+(b* b))
        action_sequence[i][1]=int((mgvar/25)*1000)
    return action_sequence

if __name__ == '__main__':
    """
    Parse(serial_message)
    """
    """
    #this is a thread to check button status at all times
    thread1 = Thread(target = button_function)
    thread1.start()
    """
    """
    #this is to grab receiver data and accelerometer
    thread2 = Thread(target = receiver_function)
    thread2.start()
    """
    # blue or green
    color = "green"

    if color == "blue":
        goal_coords = [48,0]
        goal_direction = 180
    else:
        goal_coords = [48,144]
        goal_direction = 0
    goal_coords = discretization(goal_coords)

    #made up coordinates
    ball_coords = [80,120]
    cart2_coords = [15,30]
    cart1_coords = [10,10]
    ball_coords = discretization(ball_coords)
    cart2_coords = discretization(cart2_coords)
    cart1_coords = discretization(cart1_coords)

    # depending on scenario will either play defense or offense
    """
    ball_coords = Three_scenarios(ball_coords, cart1_coords, cart2_coords, color)
    """
    # put into state space
    state = state_space()
    state = change_state(state, cart1_coords, cart2_coords, ball_coords, goal_coords)

    #A* star algorithm
    calculation = CalculationAStarAlgorithm(state, "S", "G")
    path = calculation.aStarAlgorithm()

    #make path into action_sequence
    if len(path) > 0:
        print("\n".join(calculation.renderPath(path)))
    else:
        print('failed')
    path, action_sequence = make_path(path)

    #condense action_sequence
    action_sequence = action_sequence_condense(action_sequence)
    #action_sequence readable for arduino
    action_sequence = action_seq_arduino(action_sequence)
    print(action_sequence)
    #serial stuff
    """
    arduino = serial.Serial('/dev/ttyACM0', 38400, timeout=1)
    write_read(action_sequence, arduino)
    """
