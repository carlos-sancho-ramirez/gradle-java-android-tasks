# gradle-java-android-tasks
Set of Gradle tasks that can be included in an Android project Gradle build configuration

## Objectives
When refactoring the code of an app, it may happen that we change some files and we forget to synchronise other files that were linked somehow. If we are lucky, we will detect the problem soon, maybe because the compiler will complain. In the worse scenario, none of the developers or the testers will notice it and final user will experience crashes.

This repository is intended to be included as a dependency in a Gradle build in order to ensure that some files holds what is expected, and even generate new code from those files to move the detection of the error from runtime to compile time.

## Features already implemented

### Layout wrappers creation
This is the normal way in Android of setting up a button view and bind it to a listener.

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.main_activity, container, false);
        Button button = root.findViewById(R.id.button);
        button.setOnItemClickListener(v -> doSomething());
        return root;
    }

The previous code works ok if the layout called *main_activity* certainly contains a view whose id is *button*.
When we are creating this new activity, for sure we will generate the activity and its XML at the same time, and we will test that the button reacts as expected when clicked.
However, if for any reason in the future we change the view id, or we just remove it, and we do not remember to change this Java code, this implementation will be broken.

Luckily we will detect it soon in case there was only on view with id *button* in the whole project. As it will be also removed from the R.id class. But, if we are reusing the *button* id in other XML files it will not happen and the error will pass unnoticed by the compiler, crashing the app when a test that covers that code runs, or simply the user access that activity.

This can be avoided by creating a layout wrapper. This task will generate a Java class for each XML layout file with the existing view and the proper types. Java code can use the generated wrapper instead. This is the same example as before, but using the wrapper

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        MainActivityLayout layout = MainActivityLayout.createWithLayoutInflater(inflater, container);
        layout.button().setOnItemClickListener(v -> doSomething());
        return layout.view();
    }

In this case *layout.button()* will retrieve the proper type, and we can ensure that it will never be null. If the XML is edited, the Gradle task will regenerate the wrapper updating the signature for the method accordingly, or even removing it if the view has been deleted. Then the app will not compile.

### String wrappers creation
String in Android can have placeholders like for example:

    User %1$s typed %2$s

These kind of strings must be retrieved providing the 2 values when calling *getString*

    getString(R.string.myString, "John", "Hello"); // This will result in "User John typed Hello"

If we know for sure that there is no point on calling the getString method with 0, 1 or 3 parameters, then we should enforce that it cannot happen.
This task reads all the strings in the resources checking how many placeholders each string has, and creates a Java class with a method for each string, reflecting as method parameters the number of placeholders expected.

    Strings.myString(context, "John", "Hello"); // This will result in "User John typed Hello"
